package com.alejandromacias.betflow.betting.bet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * What replaces the transaction.
 *
 * <p>Placing a bet touches three services, so there is no rollback spanning them. This row is the
 * only thing that knows how far the attempt got, and it is written <em>before</em> each step
 * rather than after: a crash then leaves it pointing at the step that was in flight, which is the
 * one still owed.
 *
 * <p><b>What it deliberately does not record yet:</b> what each step produced. It can say a saga
 * stopped while reserving funds; it cannot say which reservation to finish or undo. That is
 * enough for a happy path and not enough to recover one, and the difference will have to be
 * closed before recovery is worth claiming.
 */
@Entity
@Table(name = "bet_saga_state")
public class BetSagaState {

    @Id
    private UUID id;

    @Column(name = "bet_id", nullable = false)
    private UUID betId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private SagaStep currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BetSagaState() { }

    BetSagaState(UUID betId) {
        this.id = UUID.randomUUID();
        this.betId = betId;
        this.currentStep = SagaStep.VALIDATING_ODDS;
        this.status = SagaStatus.IN_PROGRESS;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBetId() { return betId; }
    public SagaStep getCurrentStep() { return currentStep; }
    public SagaStatus getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
}
