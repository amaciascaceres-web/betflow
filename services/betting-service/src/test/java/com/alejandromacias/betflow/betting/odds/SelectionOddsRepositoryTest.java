package com.alejandromacias.betflow.betting.odds;

import static org.assertj.core.api.Assertions.assertThat;

import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * What {@code updateIfNewer} does, on its own, against a real Postgres.
 *
 * <p>The three properties day 4 claims are properties of this one statement, so this is where they
 * are cheapest to pin down and where a wrong guess about SQL is caught. No broker, no listener, no
 * application context beyond JPA — a wrong answer here can only be the query's fault.
 *
 * <p>Schema validation does not cover it: `ddl-auto: validate` compares entities against tables and
 * never reads a query. This test is what stands in for that.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SelectionOddsRepositoryTest extends PostgresBackedTest {

    @Autowired
    private SelectionOddsRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void writesNothingWhenTheRowDoesNotExist() {
        int rowsWritten = repository.updateIfNewer(
                UUID.randomUUID(), new BigDecimal("2.500"), Instant.now());

        // Zero here means "absent", and the caller cannot tell it apart from "not newer". That
        // ambiguity is what the service's second question resolves.
        assertThat(rowsWritten).isZero();
    }

    @Test
    void overwritesWhenTheEventIsNewer() {
        Instant storedAt = Instant.parse("2026-01-01T10:00:00Z");
        UUID selectionId = given(storedAt, "2.500");

        int rowsWritten = repository.updateIfNewer(
                selectionId, new BigDecimal("3.750"), storedAt.plusSeconds(1));

        assertThat(rowsWritten).isOne();
        assertThat(reload(selectionId).odds()).isEqualByComparingTo("3.750");
    }

    @Test
    void writesNothingWhenTheEventCarriesTheStoredTimestamp() {
        Instant storedAt = Instant.parse("2026-01-01T10:00:00Z");
        UUID selectionId = given(storedAt, "2.500");

        // A redelivered event is byte-for-byte the one already applied, so this is the duplicate
        // case: the comparison is strict, and re-applying costs no write at all.
        int rowsWritten = repository.updateIfNewer(selectionId, new BigDecimal("9.990"), storedAt);

        assertThat(rowsWritten).isZero();
        assertThat(reload(selectionId).odds()).isEqualByComparingTo("2.500");
    }

    @Test
    void writesNothingWhenTheEventIsOlder() {
        Instant storedAt = Instant.parse("2026-01-01T10:00:00Z");
        UUID selectionId = given(storedAt, "2.500");

        int rowsWritten = repository.updateIfNewer(
                selectionId, new BigDecimal("9.990"), storedAt.minusSeconds(30));

        assertThat(rowsWritten).isZero();
        assertThat(reload(selectionId).odds()).isEqualByComparingTo("2.500");
    }

    @Test
    void keepsThePriceToThreeDecimalsAndRoundsBeyondThem() {
        Instant storedAt = Instant.parse("2026-01-01T10:00:00Z");
        UUID selectionId = given(storedAt, "2.500");

        repository.updateIfNewer(selectionId, new BigDecimal("2.3456"), storedAt.plusSeconds(1));

        // NUMERIC(8,3) is the contract for a price, and the column enforces it rather than the
        // application remembering to. Worth pinning: a price that silently gained digits would
        // compare unequal to itself after a round trip.
        assertThat(reload(selectionId).odds()).isEqualByComparingTo("2.346");
    }

    @Test
    void readsOneMarketWithoutTheRest() {
        UUID marketId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T10:00:00Z");
        UUID first = givenInMarket(marketId, at, "2.500");
        UUID second = givenInMarket(marketId, at, "3.500");
        givenInMarket(UUID.randomUUID(), at, "4.500");

        assertThat(repository.findViewsByMarketId(marketId))
                .extracting(SelectionOddsDto::selectionId)
                .containsExactlyInAnyOrder(first, second);
    }

    private UUID given(Instant oddsUpdatedAt, String odds) {
        return givenInMarket(UUID.randomUUID(), oddsUpdatedAt, odds);
    }

    private UUID givenInMarket(UUID marketId, Instant oddsUpdatedAt, String odds) {
        UUID selectionId = UUID.randomUUID();
        entityManager.persist(new SelectionOdds(
                selectionId, marketId, new BigDecimal(odds), oddsUpdatedAt));
        entityManager.flush();
        return selectionId;
    }

    /**
     * Reads the row, and can only read the row. A bulk update goes straight to the database
     * without telling the persistence context, so asking for the entity here would hand back the
     * stale instance seeded above — the query would run, and its result would be discarded in
     * favour of the one already in memory. A constructor expression has no identity to preserve.
     */
    private SelectionOddsDto reload(UUID selectionId) {
        return repository.findViewBySelectionId(selectionId).orElseThrow();
    }
}
