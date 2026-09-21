package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Asked for more than there is. A business answer, not a fault: retrying it would only produce
 * the same answer, and whoever called has to decide what to do instead.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID walletId, BigDecimal balance, BigDecimal requested) {
        super("wallet " + walletId + " holds " + balance + ", cannot reserve " + requested);
    }
}
