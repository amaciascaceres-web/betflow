package com.alejandromacias.betflow.betting.remote;

import java.util.UUID;

/**
 * Wallet returns rather more than this — the wallet, the bet, the amount, the status — and
 * betting declares only the field it uses. Unknown properties are ignored, so the contract this
 * service depends on is exactly as wide as this record and no wider.
 */
public record ReservedFunds(UUID id) {
}
