package com.alejandromacias.betflow.wallet.funds;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID reservationId) {
        super("no reservation " + reservationId);
    }
}
