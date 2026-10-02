package com.alejandromacias.betflow.betting.bet;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Writes are statements, not mutations of a loaded object.
 *
 * <p>JPA would write these by dirty checking: load the row, change a field, and let the flush
 * notice at commit. It works, and calling {@code save()} afterwards does not make it any less
 * implicit — on a managed entity that call is a no-op and the update still comes from the flush.
 * Stating the update instead makes every write greppable, and matches how wallet and the odds
 * projection already write.
 *
 * <p>Nothing here is guarded yet: there is one decider, so there is no second conclusion to
 * exclude. When that changes, the guard is a condition on this {@code WHERE} rather than a
 * rewrite.
 */
public interface BetSagaStateRepository extends Repository<BetSagaState, UUID> {

    /** New row, so this is a real insert rather than a merge that changes nothing. */
    BetSagaState save(BetSagaState state);

    Optional<BetSagaState> findByBetId(UUID betId);

    @Modifying
    @Query("""
            UPDATE BetSagaState s
               SET s.currentStep = :step,
                   s.updatedAt = :now
             WHERE s.betId = :betId
            """)
    int enterStep(@Param("betId") UUID betId, @Param("step") SagaStep step,
                  @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE BetSagaState s
               SET s.status = :status,
                   s.updatedAt = :now
             WHERE s.betId = :betId
            """)
    int settle(@Param("betId") UUID betId, @Param("status") SagaStatus status,
               @Param("now") Instant now);
}
