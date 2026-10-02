package com.alejandromacias.betflow.betting.bet;

import java.util.UUID;

/** Sportsbook has no such selection, or not in the market the request named. */
public class SelectionNotFoundException extends RuntimeException {

    private final UUID betId;

    public SelectionNotFoundException(UUID betId, UUID marketId, UUID selectionId) {
        super("bet " + betId + ": no selection " + selectionId + " in market " + marketId);
        this.betId = betId;
    }

    public UUID getBetId() {
        return betId;
    }
}
