package com.alejandromacias.betflow.betting.bet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every local write the saga makes, each one its own transaction.
 *
 * <p>It is a separate bean for a reason that is easy to get wrong. A saga is not a transaction:
 * it is a sequence of local transactions with remote calls between them, and each write has to
 * commit on its own so that a crash leaves a record of how far the attempt got. Wrapping
 * {@link BettingSagaOrchestrator#placeBet} in {@code @Transactional} would look tidier and would
 * destroy exactly that: nothing would be visible until the end, so the only state a failure could
 * leave behind is none.
 *
 * <p>And these could not simply be private methods on the orchestrator. Spring's transactions are
 * applied by a proxy, so a method calling its own {@code @Transactional} method bypasses it
 * entirely — the annotation compiles, reads correctly, and does nothing.
 *
 * <p>The writes are statements rather than mutations of loaded entities. Both work; the statement
 * is the one a reader can see, and it is what the rest of this project already does.
 */
@Component
class BetSagaStore {

    private final BetRepository bets;
    private final BetSagaStateRepository sagas;

    BetSagaStore(BetRepository bets, BetSagaStateRepository sagas) {
        this.bets = bets;
        this.sagas = sagas;
    }

    /**
     * The bet exists before it is worth anything, and the saga before its first step runs. A bet
     * recorded only on success would leave every failure invisible, which is the opposite of what
     * a saga is for.
     *
     * <p>Both rows are new, so {@code save} here is a genuine insert, and both are written in one
     * transaction — which is only possible because the saga's state lives in the same schema as
     * what it is a saga about.
     */
    @Transactional
    Bet start(BetRequest request) {
        Bet bet = bets.save(new Bet(UUID.randomUUID(), request.userId(), request.marketId(),
                request.selectionId(), request.amount()));
        sagas.save(new BetSagaState(bet.getId()));
        return bet;
    }

    @Transactional
    void recordAcceptedOdds(UUID betId, BigDecimal odds) {
        bets.recordAppliedOdds(betId, odds);
    }

    /** Written <em>before</em> the step runs, so a crash points at what was in flight. */
    @Transactional
    void enter(UUID betId, SagaStep step) {
        sagas.enterStep(betId, step, Instant.now());
    }

    @Transactional
    Bet complete(UUID betId) {
        bets.settle(betId, BetStatus.CONFIRMED);
        sagas.settle(betId, SagaStatus.COMPLETED, Instant.now());
        return reload(betId);
    }

    /**
     * Closes a saga that cannot go on. Reached today only when a step came back with a refusal —
     * nothing has been reserved at that point, so this is the whole of the failure handling and
     * not half of it. Leaving the bet PENDING instead would create an abandoned row on purpose.
     */
    @Transactional
    void fail(UUID betId) {
        bets.settle(betId, BetStatus.FAILED);
        sagas.settle(betId, SagaStatus.FAILED, Instant.now());
    }

    /**
     * Read after the statements, never before: a bulk update writes underneath the persistence
     * context, so an entity loaded first would still be showing what the statement has just
     * changed.
     */
    private Bet reload(UUID betId) {
        return bets.findById(betId).orElseThrow(() -> new IllegalStateException("no bet " + betId));
    }
}
