package com.alejandromacias.betflow.betting.bet;

import java.math.BigDecimal;
import java.util.UUID;

/** What a caller gets back: the bet, as a value, with the price it was actually accepted at. */
public record BetResponse(
        UUID betId,
        UUID userId,
        UUID marketId,
        UUID selectionId,
        BigDecimal amount,
        BigDecimal appliedOdds,
        BetStatus status) {

    static BetResponse of(Bet bet) {
        return new BetResponse(bet.getId(), bet.getUserId(), bet.getMarketId(),
                bet.getSelectionId(), bet.getAmount(), bet.getAppliedOdds(), bet.getStatus());
    }
}
