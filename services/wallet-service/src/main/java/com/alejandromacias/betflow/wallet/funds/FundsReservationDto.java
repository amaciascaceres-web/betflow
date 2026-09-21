package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A reservation as a value, built by the query that reads it.
 *
 * <p>The status is the field that makes this worth doing. It is changed by a guarded statement
 * that goes straight to the database, so an entity loaded before that statement would go on
 * reporting the state the transition has just moved away from, and asking for it again would hand
 * back the same stale instance rather than the row. A record has no identity to preserve and
 * nothing to leave behind.
 */
public record FundsReservationDto(
        UUID id,
        UUID walletId,
        UUID betId,
        BigDecimal amount,
        FundsReservationStatus status) {
}
