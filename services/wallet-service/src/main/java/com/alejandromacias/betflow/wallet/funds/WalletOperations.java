package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Where the optimistic lock failure is handled, and the reason this is a separate class rather
 * than a {@code try} inside {@link WalletService}.
 *
 * <p>Three things have to be true at once, and each one rules out the obvious shortcut:
 *
 * <ul>
 *   <li><b>The failure surfaces at commit</b>, not at the statement that caused it. Hibernate
 *       writes and checks the version when the transaction flushes, so by the time it is thrown
 *       the service method has already returned. A {@code catch} inside it would never fire.
 *   <li><b>A retry has to start a new transaction.</b> The failed one is marked rollback-only and
 *       can do nothing more; only a fresh one re-reads the balance, which is the entire point —
 *       the second attempt has to see what the first writer left behind.
 *   <li><b>Only the collision is retried.</b> {@link InsufficientFundsException} is the correct
 *       answer to the question asked and is allowed straight through. Retrying it would ask the
 *       same question again and get the same answer.
 * </ul>
 *
 * <p>Those combine into the behaviour the domain actually wants: two bets of 60 against a balance
 * of 100 collide, one wins, the other retries, reads 40 and is refused for want of funds. Never
 * 100 − 60 − 60, and never an error the user did not cause.
 *
 * <p>The failure is caught as Spring's {@link ObjectOptimisticLockingFailureException}, which is
 * what the exception translation turns the JPA one into. Catching {@code OptimisticLockException}
 * would compile and never match.
 */
@Component
public class WalletOperations {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 80;

    private static final Logger log = LoggerFactory.getLogger(WalletOperations.class);

    private final WalletService walletService;

    WalletOperations(WalletService walletService) {
        this.walletService = walletService;
    }

    public FundsReservationDto reserveFunds(UUID walletId, UUID betId, BigDecimal amount) {
        return retrying("reserveFunds", "wallet " + walletId + " for bet " + betId,
                () -> walletService.reserveFunds(walletId, betId, amount));
    }

    public void releaseFunds(UUID reservationId) {
        retrying("releaseFunds", "reservation " + reservationId, () -> {
            walletService.releaseFunds(reservationId);
            return null;
        });
    }

    /**
     * Straight through, with no retry, because there is nothing here to collide with: confirming
     * touches the reservation's status and never the balance, and {@code FundsReservation} carries
     * no version. Its guarded transition already makes a repeat harmless.
     */
    public void confirmFunds(UUID reservationId) {
        walletService.confirmFunds(reservationId);
    }

    /**
     * Both the operation and what it contended for go into every line. Which wallet was busy is
     * only half the question an on-call engineer has: a reservation retried and a release retried
     * are different problems, and the identifiers are what tie the line to the request that
     * produced it.
     *
     * <p>A retry that succeeds is expected behaviour, so it is logged at {@code INFO} and could
     * defensibly be {@code DEBUG} on a busier system. Giving up is not expected: it means one
     * wallet stayed contended for as long as this was willing to wait, which is worth a
     * {@code WARN} whether or not the caller logs the exception.
     */
    private <T> T retrying(String operation, String subject, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException collision) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Giving up {} on {} after {} optimistic lock collisions",
                            operation, subject, MAX_ATTEMPTS);
                    throw new WalletBusyException(subject, MAX_ATTEMPTS, collision);
                }
                log.info("Optimistic lock collision on {} during {}, attempt {} of {}, retrying",
                        subject, operation, attempt, MAX_ATTEMPTS);
                pauseBeforeRetrying();
            }
        }
    }

    /**
     * A fixed pause, not exponential backoff. Contention on one wallet is short-lived by nature —
     * the writer that won is already committing — so the extra machinery would buy nothing here.
     */
    private void pauseBeforeRetrying() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
