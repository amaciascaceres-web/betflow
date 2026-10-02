package com.alejandromacias.betflow.betting.remote;

import java.math.BigDecimal;
import java.util.UUID;

public record ReserveFundsRequest(UUID walletId, UUID betId, BigDecimal amount) {
}
