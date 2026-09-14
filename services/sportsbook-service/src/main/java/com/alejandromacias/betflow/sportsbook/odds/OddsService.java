package com.alejandromacias.betflow.sportsbook.odds;

import com.alejandromacias.betflow.sportsbook.catalog.Selection;
import com.alejandromacias.betflow.sportsbook.catalog.SelectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OddsService {

    private final SelectionRepository selections;
    private final OddsChangedProducer producer;

    OddsService(SelectionRepository selections, OddsChangedProducer producer) {
        this.selections = selections;
        this.producer = producer;
    }

    /**
     * Moves a price and announces it.
     *
     * <p>These are two writes to two systems with no transaction spanning them — the dual-write
     * problem. Publishing first risks announcing a price the authority does not hold; committing
     * first risks a change nobody hears about. Neither order removes the window; only an outbox
     * would, and that is not introduced yet.
     *
     * <p>The database is therefore committed first and the event published afterwards, via an
     * after-commit hook so that a rolled-back change is never announced. The remaining exposure
     * is a lost announcement, which is tolerable here for two specific reasons: the next price
     * change on the same selection corrects the gap within seconds, and the check that actually
     * protects money — validating the price when a bet is placed — asks this service directly
     * rather than trusting a consumer's copy. See ADR-002.
     *
     * <p>Known simplification: a real market reprices every selection together when one of them
     * moves (see ADR-002, decision 2). This moves only the selection given, with no effect on
     * its siblings. That is deliberate — the goal here is to exercise the event mechanics
     * (ordering, partitioning, the dual write), not to model real odds-setting logic.
     */
    @Transactional
    public void changeOdds(UUID selectionId, BigDecimal newOdds) {
        Selection selection = selections.findById(selectionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown selection " + selectionId));

        selection.changeOddsTo(newOdds, Instant.now());
        selections.save(selection);

        OddsChangedEvent event = OddsChangedEvent.of(
                selection.getMarketId(), selection.getId(), selection.getCurrentOdds());

        publishAfterCommit(event);
    }

    private void publishAfterCommit(OddsChangedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            producer.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                producer.publish(event);
            }
        });
    }
}
