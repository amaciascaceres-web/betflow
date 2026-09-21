package com.alejandromacias.betflow.wallet.funds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A hold on funds: the money is out of reach for any other bet, but the spend is not sealed until
 * the bet is confirmed — or handed back when it fails.
 *
 * <p>The status is never changed through this object. Transitions go through a guarded statement
 * on {@link FundsReservationRepository} that only fires from the state it expects, which is what
 * makes confirming or releasing twice safe. Reading a status, deciding in Java and writing it back
 * would put a race where there is currently none.
 */
@Entity
@Table(name = "funds_reservation")
public class FundsReservation {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "bet_id", nullable = false)
    private UUID betId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FundsReservationStatus status;

    protected FundsReservation() { }

    FundsReservation(UUID walletId, UUID betId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.walletId = walletId;
        this.betId = betId;
        this.amount = amount;
        this.status = FundsReservationStatus.PENDING;
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public UUID getBetId() { return betId; }
    public BigDecimal getAmount() { return amount; }
    public FundsReservationStatus getStatus() { return status; }
}
