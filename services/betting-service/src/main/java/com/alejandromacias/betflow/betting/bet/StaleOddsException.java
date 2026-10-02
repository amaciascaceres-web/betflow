package com.alejandromacias.betflow.betting.bet;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The price moved between the user seeing it and confirming.
 *
 * <p>Accepting a bet at odds that no longer exist is as much a business failure as accepting one
 * without funds, and it is why the first step is a call and not a lookup. It is also the only
 * failure this day handles, for a good reason: it happens before anything has been reserved, so
 * refusing costs nothing and undoes nothing.
 *
 * <p>Carries the bet, not only the selection. The bet is the identifier every other service and
 * every other log line will be using — the same lesson wallet's refusals learned.
 */
public class StaleOddsException extends RuntimeException {

    private final UUID betId;

    public StaleOddsException(UUID betId, UUID selectionId, BigDecimal expected, BigDecimal current) {
        super("bet " + betId + ": selection " + selectionId + " is priced at " + current
                + ", not " + expected);
        this.betId = betId;
    }

    public UUID getBetId() {
        return betId;
    }
}
