package com.alejandromacias.betflow.wallet.funds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement, written once and never touched again.
 *
 * <p>A real book does not store a number and trust it; it stores what happened and derives the
 * number. That is what makes a balance auditable and reconstructible at any past point, and it is
 * why {@link LedgerEntryRepository} offers no way to change a row: immutability is enforced by the
 * absence of a method, not by a comment asking nicely.
 *
 * <p>The amount is signed, so the balance is a plain sum. The field is {@code timestamp} because
 * that is the name the domain uses; the column is {@code occurred_at} because {@code timestamp}
 * doubles as a type name in SQL and reads badly in a schema.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerEntryType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant timestamp;

    /** Null for a top-up, which belongs to no bet. */
    @Column(name = "bet_reference_id")
    private UUID betReferenceId;

    protected LedgerEntry() { }

    LedgerEntry(UUID walletId, LedgerEntryType type, BigDecimal amount, UUID betReferenceId) {
        this.id = UUID.randomUUID();
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.timestamp = Instant.now();
        this.betReferenceId = betReferenceId;
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public LedgerEntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public Instant getTimestamp() { return timestamp; }
    public UUID getBetReferenceId() { return betReferenceId; }
}
