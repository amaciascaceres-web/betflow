package com.alejandromacias.betflow.betting.remote;

/**
 * Wallet answered "no" rather than failing.
 *
 * <p>Raised by {@link WalletClient} so the orchestrator never has to read a status code. Keeping
 * HTTP inside the client is what lets day 9 move this call onto a queue without the saga noticing
 * — a refusal will arrive as a message instead of a response, and the orchestrator's branch
 * stays as it is.
 */
public class FundsRefusedException extends RuntimeException {

    public FundsRefusedException(String walletSaid) {
        super(walletSaid);
    }
}
