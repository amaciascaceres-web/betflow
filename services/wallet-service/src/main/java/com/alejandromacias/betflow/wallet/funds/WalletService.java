package com.alejandromacias.betflow.wallet.funds;

import static com.alejandromacias.betflow.wallet.funds.FundsReservationStatus.CONFIRMED;
import static com.alejandromacias.betflow.wallet.funds.FundsReservationStatus.PENDING;
import static com.alejandromacias.betflow.wallet.funds.FundsReservationStatus.RELEASED;

import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three operations the rest of the system performs on a balance, each one writing the wallet
 * and its ledger entry in the same transaction so the two can never disagree.
 *
 * <p>Two different mechanisms guard two different things, and the split is deliberate:
 *
 * <ul>
 *   <li><b>The balance</b> is guarded by the aggregate's {@code @Version}. Reserving has to read,
 *       decide and write, so the version is what stops two readers of the same balance from both
 *       writing. It covers the whole row, which a condition naming one column would not.
 *   <li><b>The reservation's status</b> is guarded by a conditional update that fires only from
 *       the state it expects. That costs nothing and buys idempotence: confirming or releasing
 *       twice is safe, which matters for an operation that will one day arrive as a message.
 * </ul>
 *
 * <p>Nothing here catches the optimistic lock failure. It is not thrown by any statement in this
 * class — it surfaces when the transaction commits, by which point this method has returned — so
 * the retry has to live outside. See {@link WalletOperations}.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final FundsReservationRepository reservations;
    private final LedgerEntryRepository ledger;

    WalletService(WalletRepository wallets, FundsReservationRepository reservations,
            LedgerEntryRepository ledger) {
        this.wallets = wallets;
        this.reservations = reservations;
        this.ledger = ledger;
    }

    /**
     * Takes the amount out of the available balance and records the hold.
     *
     * <p>The unique constraint on {@code bet_id} is doing quiet work here: if this arrives twice
     * for the same bet, the second insert fails and takes its own balance deduction down with it,
     * because both are in one transaction. The money can only leave once however many times the
     * request arrives.
     */
    @Transactional
    public FundsReservationDto reserveFunds(UUID walletId, UUID betId, BigDecimal amount) {
        Wallet wallet = wallets.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        wallet.reserve(amount);

        FundsReservation reservation = reservations.save(new FundsReservation(walletId, betId, amount));
        ledger.save(new LedgerEntry(walletId, LedgerEntryType.RESERVATION, amount.negate(), betId));

        // Built from the entity rather than read back, and safe because it is the object just
        // constructed here — not one loaded from a row something else could have moved.
        return new FundsReservationDto(reservation.getId(), walletId, betId, amount,
                reservation.getStatus());
    }

    /**
     * Seals the hold. The balance is untouched — the money left when it was reserved — so the
     * entry is neutral and exists to record that the hold became a real spend.
     */
    @Transactional
    public void confirmFunds(UUID reservationId) {
        if (reservations.transition(reservationId, PENDING, CONFIRMED) == 0) {
            requireAlreadyIn(reservationId, CONFIRMED);
            return;
        }
        FundsReservationDto reservation = load(reservationId);
        ledger.save(new LedgerEntry(reservation.walletId(), LedgerEntryType.CONFIRMATION,
                BigDecimal.ZERO, reservation.betId()));
    }

    /** Hands an unspent hold back to the available balance. */
    @Transactional
    public void releaseFunds(UUID reservationId) {
        if (reservations.transition(reservationId, PENDING, RELEASED) == 0) {
            requireAlreadyIn(reservationId, RELEASED);
            return;
        }
        FundsReservationDto reservation = load(reservationId);
        Wallet wallet = wallets.findById(reservation.walletId())
                .orElseThrow(() -> new WalletNotFoundException(reservation.walletId()));
        wallet.release(reservation.amount());
        ledger.save(new LedgerEntry(wallet.getId(), LedgerEntryType.RELEASE,
                reservation.amount(), reservation.betId()));
    }

    /**
     * Separates the two reasons a guarded transition writes nothing, and records both.
     *
     * <p>Landing where the caller wanted to land is a repeat — normal from the day these arrive as
     * messages, and worth a line only so that a reconciliation has something to count. Landing
     * anywhere else means two contradictory decisions reached this reservation, and the one that
     * lost is being told so.
     *
     * <p>The warning is logged here rather than left to whoever catches the exception, because
     * the service that knows what happened should be the one that says so. Otherwise the only
     * trace of a wallet-side contradiction lives in the caller's log, and wallet — the service
     * that actually arbitrated — is silent about it.
     */
    private void requireAlreadyIn(UUID reservationId, FundsReservationStatus target) {
        FundsReservationDto reservation = load(reservationId);
        if (reservation.status() == target) {
            log.info("Ignored a repeated {} of reservation {} (bet {}), already there",
                    target, reservationId, reservation.betId());
            return;
        }
        log.warn("Refused to move reservation {} (bet {}) to {}: expected {} but found {}. "
                        + "Two contradictory decisions reached this reservation",
                reservationId, reservation.betId(), target, PENDING, reservation.status());
        throw new IllegalReservationStateException(
                reservationId, reservation.betId(), reservation.status(), PENDING);
    }

    /**
     * A value, not the entity. That is what removes the hazard rather than documenting it: a
     * guarded update writes underneath the persistence context, so an entity read here could be
     * showing the status the transition has just moved away from — and asking again would return
     * the same stale instance instead of the row.
     */
    private FundsReservationDto load(UUID reservationId) {
        return reservations.findViewById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }
}
