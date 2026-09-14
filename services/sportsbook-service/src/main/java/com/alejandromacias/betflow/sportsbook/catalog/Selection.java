package com.alejandromacias.betflow.sportsbook.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "selection")
public class Selection {

    private static final int ODDS_SCALE = 2;

    @Id
    private UUID id;

    @Column(name = "market_id", nullable = false)
    private UUID marketId;

    @Column(nullable = false)
    private String name;

    /**
     * The price a bet would be accepted at right now. This service is the authority on it, which
     * is what lets it answer the synchronous "is this price still current?" check when a bet is
     * placed. The published event announces that this value changed; it does not replace it.
     */
    @Column(name = "current_odds", nullable = false)
    private BigDecimal currentOdds;

    @Column(name = "odds_updated_at", nullable = false)
    private Instant oddsUpdatedAt;

    protected Selection() { }

    /**
     * Replaces the price outright rather than adjusting it by a delta. Expressing the change as
     * "set to X" is what makes applying it twice indistinguishable from applying it once, which
     * matters to every consumer of the resulting event.
     */
    public void changeOddsTo(BigDecimal newOdds, Instant at) {
        this.currentOdds = newOdds.setScale(ODDS_SCALE, RoundingMode.HALF_UP);
        this.oddsUpdatedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getMarketId() { return marketId; }
    public String getName() { return name; }
    public BigDecimal getCurrentOdds() { return currentOdds; }
    public Instant getOddsUpdatedAt() { return oddsUpdatedAt; }
}
