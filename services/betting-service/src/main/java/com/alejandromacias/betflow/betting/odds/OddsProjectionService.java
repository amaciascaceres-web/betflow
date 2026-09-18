package com.alejandromacias.betflow.betting.odds;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an {@code OddsChanged} fact to betting's local projection.
 *
 * <p>This service has no deduplication table, and that is the decision of the day rather than an
 * omission. Kafka delivers at least once, so this method will be handed the same event twice
 * sooner or later — but the effect is a replacement, so the second application lands on the state
 * the first one produced and changes nothing. A dedup table would add a write to every event to
 * protect something already protected by the shape of the effect, and would then need a retention
 * policy of its own. Where the effect is accumulative — reserving funds — that trade goes the
 * other way, and the table earns its place. See ADR-004.
 */
@Service
public class OddsProjectionService {

    private final SelectionOddsRepository repository;

    OddsProjectionService(SelectionOddsRepository repository) {
        this.repository = repository;
    }

    /**
     * Postgres could settle this in one statement with {@code INSERT ... ON CONFLICT}. Staying
     * inside JPQL costs the ambiguity handled below: an update that writes nothing means either
     * "the row is not there" or "the event is not newer", and only a second question separates
     * them.
     *
     * <p>The first branch is the one that runs almost always, and it is a single statement whose
     * condition the database evaluates. The other two run once per selection in the system's
     * lifetime, or on an event that was never going to change anything.
     *
     * <p>The insert is not atomic with the check that preceded it. Nothing can enter that gap
     * here, because a partition is read by one consumer of the group and a selection belongs to
     * one market, which is the partition key — so one thread owns every event for a given
     * selection. That safety comes from an invariant of the architecture rather than from the
     * database, and nothing enforces it: a second writer, an admin correction or a backfill job
     * would reopen the gap without any of this code changing. Recorded in ADR-004.
     *
     * @return {@code true} if the projection moved, {@code false} if the event was a duplicate or
     *         older than what is stored — both of which are correct outcomes, not failures.
     */
    @Transactional
    public boolean apply(OddsChangedEvent event) {
        int rowsWritten = repository.updateIfNewer(
                event.selectionId(), event.odds(), event.timestamp());
        if (rowsWritten == 1) {
            return true;
        }
        if (repository.existsById(event.selectionId())) {
            return false;
        }

        // First time this selection is seen. save() goes through merge() rather than persist(),
        // because the id is assigned rather than generated, so it costs one extra select — once
        // per selection, ever. Cheaper than the machinery that would avoid it.
        repository.save(new SelectionOdds(
                event.selectionId(), event.marketId(), event.odds(), event.timestamp()));
        return true;
    }
}
