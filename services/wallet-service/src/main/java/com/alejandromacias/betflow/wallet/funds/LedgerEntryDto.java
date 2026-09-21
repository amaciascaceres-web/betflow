package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger movement as a value. Reading history should not hand out something writable: an entry
 * adjusted in passing would be flushed at commit as an {@code UPDATE} on an append-only table,
 * with no {@code save} call anywhere to find it by.
 */
public record LedgerEntryDto(
        UUID id,
        UUID walletId,
        LedgerEntryType type,
        BigDecimal amount,
        Instant timestamp,
        UUID betReferenceId) {
}
