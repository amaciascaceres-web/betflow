package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param betId the caller's own identifier for what the money is for. It is not decoration: it is
 *              the unique key that stops the same bet reserving twice, so a caller that invents a
 *              fresh one per attempt defeats the protection.
 */
public record ReserveFundsRequest(UUID walletId, UUID betId, BigDecimal amount) {
}
