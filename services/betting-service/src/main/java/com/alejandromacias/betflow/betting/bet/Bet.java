package com.alejandromacias.betflow.betting.bet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A bet, which exists before it is worth anything.
 *
 * <p>It is written as {@code PENDING} the moment the request arrives, before the price has been
 * checked or the money reserved. That is deliberate: a bet nobody recorded until it succeeded
 * would leave the failures invisible, and the failures are what a saga exists to manage.
 *
 * <p>Carries no behaviour beyond being created. Every later change is a statement on
 * {@link BetRepository}, because there is no invariant here to protect — unlike a balance, which
 * nobody may overdraw and whose rule therefore lives on the entity that owns it.
 */
@Entity
@Table(name = "bet")
public class Bet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "market_id", nullable = false)
    private UUID marketId;

    @Column(name = "selection_id", nullable = false)
    private UUID selectionId;

    @Column(nullable = false)
    private BigDecimal amount;

    /** The price the bet was accepted at, which is only known once sportsbook has confirmed it. */
    @Column(name = "applied_odds")
    private BigDecimal appliedOdds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BetStatus status;

    protected Bet() { }

    Bet(UUID id, UUID userId, UUID marketId, UUID selectionId, BigDecimal amount) {
        this.id = id;
        this.userId = userId;
        this.marketId = marketId;
        this.selectionId = selectionId;
        this.amount = amount;
        this.status = BetStatus.PENDING;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getMarketId() { return marketId; }
    public UUID getSelectionId() { return selectionId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getAppliedOdds() { return appliedOdds; }
    public BetStatus getStatus() { return status; }
}
