package com.alejandromacias.betflow.betting.bet;

import java.util.UUID;

/**
 * Wallet refused the stake. Betting's own type, not wallet's: this service depends on wallet's
 * HTTP contract, not on its classes, and the same reasoning that keeps the event schema separate
 * applies to a refusal.
 *
 * <p>A business answer, like {@link StaleOddsException}, and handled on the same day for the same
 * reason: it is discovered before anything is reserved, so refusing costs nothing and undoes
 * nothing. What it is <em>not</em> is a failure of this service — asking again would be told the
 * same thing.
 *
 * <p>Carries no detail from wallet. What wallet said is logged where it was received; repeating it
 * here would put another service's wording and ids into this one's public contract, where a caller
 * could start depending on them.
 */
public class InsufficientFundsException extends RuntimeException {

    private final UUID betId;

    public InsufficientFundsException(UUID betId) {
        super("bet " + betId + " was refused: insufficient funds");
        this.betId = betId;
    }

    public UUID getBetId() {
        return betId;
    }
}
