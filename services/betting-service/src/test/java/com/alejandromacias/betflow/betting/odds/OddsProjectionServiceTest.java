package com.alejandromacias.betflow.betting.odds;

import static org.assertj.core.api.Assertions.assertThat;

import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three branches of {@code apply} and the one property that no single branch expresses.
 *
 * <p>What a branch does with one event is settled here; what a <em>statement</em> does with one
 * row is settled in {@link SelectionOddsRepositoryTest}, and the two are kept apart so that a
 * failure names its own cause.
 *
 * <p>No broker. Nothing here depends on Kafka: its only contribution is handing the same event
 * over more than once, and calling the service twice reproduces that exactly, in a quarter of a
 * second and with nothing to wait for.
 *
 * <p>The class opts out of the rollback-per-test that {@code @DataJpaTest} provides: what is under
 * test includes where the service's own transaction begins and ends, and a surrounding transaction
 * would swallow that. Isolation comes from every test using its own identifiers instead.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OddsProjectionService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OddsProjectionServiceTest extends PostgresBackedTest {

    @Autowired
    private OddsProjectionService service;

    @Autowired
    private SelectionOddsRepository repository;

    /**
     * The branch reached when the conditional update writes nothing because the row is absent. It
     * is also the only place the event-to-row mapping is checked: a selection id written into the
     * market column would pass every other test in this class.
     */
    @Test
    void theFirstEventForASelectionCreatesItsRow() {
        Instant changedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OddsChangedEvent event = eventFor(UUID.randomUUID(), UUID.randomUUID(), "2.350", changedAt);

        assertThat(service.apply(event)).isTrue();

        SelectionOddsDto stored = repository.findViewBySelectionId(event.selectionId()).orElseThrow();
        assertThat(stored.marketId()).isEqualTo(event.marketId());
        assertThat(stored.odds()).isEqualByComparingTo("2.350");
        assertThat(stored.oddsUpdatedAt()).isEqualTo(changedAt);
    }

    /**
     * The branch reached when the conditional update writes nothing and the row is there: return
     * without touching it. Falling through to {@code save()} instead would overwrite a current
     * price with a stale one, and would do so through a method whose name says "save", not
     * "clobber".
     *
     * <p>A redelivery is the case that actually happens — at-least-once guarantees this exact
     * event arrives twice sooner or later. An event that is merely older reaches the same branch
     * by the same route, so it is not repeated here; where the two genuinely differ is inside the
     * statement, and that is {@link SelectionOddsRepositoryTest}'s job.
     */
    @Test
    void anEventThatIsNotNewerLeavesTheRowAlone() {
        UUID marketId = UUID.randomUUID();
        OddsChangedEvent event = eventFor(marketId, UUID.randomUUID(), "2.350", Instant.now());

        service.apply(event);
        Map<UUID, String> afterFirstApply = projectionOf(marketId);

        assertThat(service.apply(event)).isFalse();
        assertThat(projectionOf(marketId)).isEqualTo(afterFirstApply);
    }

    /**
     * Convergence: applying a sequence, then applying it again, lands on the state the first pass
     * produced. It is the property the decision to carry no deduplication table rests on —
     * at-least-once may redeliver one record, a block of them, or the whole log after an offset
     * reset, and none of those can corrupt a projection that converges.
     *
     * <p><b>What it is not evidence for.</b> Convergence is stability, not correctness: a
     * projection that settles on wrong values settles just as firmly. Deleting the selection
     * filter from the conditional update, so that every older row is written, was confirmed to
     * leave this test passing while three others failed. Whether the values are right belongs to
     * the tests around it; this one owns only whether they stop moving.
     *
     * <p><b>And today it is largely derivable.</b> Given a statement that either writes a newer
     * value or writes nothing, and a service that never inserts over an existing row — both
     * asserted already — a second pass provably cannot move anything. What it adds over the
     * single-event case above is the sequence: several selections, several moves, interleaved, so
     * that stability is checked where rows could disturb one another. That, and stating the
     * requirement in the requirement's own words, which is what makes it the decision's
     * executable form rather than a restatement of the code.
     */
    @Test
    void replayingTheWholeStreamConvergesOnTheSameState() {
        UUID marketId = UUID.randomUUID();
        List<OddsChangedEvent> stream = streamOfPriceMoves(marketId);

        stream.forEach(service::apply);
        Map<UUID, String> beforeReplay = projectionOf(marketId);

        stream.forEach(service::apply);

        assertThat(projectionOf(marketId)).isEqualTo(beforeReplay);
    }

    private List<OddsChangedEvent> streamOfPriceMoves(UUID marketId) {
        List<UUID> selections = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Instant start = Instant.now();
        List<OddsChangedEvent> stream = new ArrayList<>();
        for (int move = 0; move < 4; move++) {
            for (int selection = 0; selection < selections.size(); selection++) {
                stream.add(eventFor(marketId, selections.get(selection),
                        "1." + (500 + move * 10 + selection),
                        start.plusMillis(move * 1000L + selection)));
            }
        }
        return stream;
    }

    private static OddsChangedEvent eventFor(UUID marketId, UUID selectionId, String odds, Instant at) {
        return new OddsChangedEvent(UUID.randomUUID(), marketId, selectionId, new BigDecimal(odds), at);
    }

    /** The projection as a value, so two moments in time can be compared for equality. */
    private Map<UUID, String> projectionOf(UUID marketId) {
        Map<UUID, String> snapshot = new HashMap<>();
        for (SelectionOddsDto row : repository.findViewsByMarketId(marketId)) {
            snapshot.put(row.selectionId(), row.odds().toPlainString() + "@" + row.oddsUpdatedAt());
        }
        return snapshot;
    }
}
