package com.alejandromacias.betflow.wallet.funds;

/**
 * Every attempt collided with another writer. Unlike {@link InsufficientFundsException} this says
 * nothing about the business: the operation never got as far as being decided, and trying again
 * later is a reasonable thing to do.
 */
public class WalletBusyException extends RuntimeException {

    public WalletBusyException(String subject, int attempts, Throwable cause) {
        super(subject + " stayed contended across " + attempts + " attempts", cause);
    }
}
