package com.alejandromacias.betflow.wallet.funds;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID walletId) {
        super("no wallet " + walletId);
    }
}
