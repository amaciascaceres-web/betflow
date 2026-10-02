package com.alejandromacias.betflow.betting.bet;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param walletId    which wallet pays. Carried by the caller because resolving a user's wallet
 *                    is wallet's business, and nothing has asked for that lookup — a
 *                    simplification worth knowing about rather than a claim that real callers
 *                    would know this.
 * @param expectedOdds the price the user was shown. The whole point of the first step is that
 *                     this may no longer be true by the time they pressed the button.
 */
public record BetRequest(
        UUID userId,
        UUID walletId,
        UUID marketId,
        UUID selectionId,
        BigDecimal amount,
        BigDecimal expectedOdds) {
}
