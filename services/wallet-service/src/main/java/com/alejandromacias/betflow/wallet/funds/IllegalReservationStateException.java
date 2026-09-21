package com.alejandromacias.betflow.wallet.funds;

import java.util.UUID;

/**
 * A transition that cannot happen: confirming a reservation that was already released, or
 * releasing one already confirmed. Distinct from doing the same thing twice, which is allowed and
 * silent.
 *
 * <p>Carries the bet as well as the reservation on purpose. The reservation id is wallet's
 * handle; the bet is what betting and the saga think in, and without it the two halves of an
 * investigation have no key in common.
 */
public class IllegalReservationStateException extends RuntimeException {

    public IllegalReservationStateException(UUID reservationId, UUID betId,
            FundsReservationStatus status, FundsReservationStatus expected) {
        super("reservation " + reservationId + " (bet " + betId + ") is " + status
                + ", expected " + expected);
    }
}
