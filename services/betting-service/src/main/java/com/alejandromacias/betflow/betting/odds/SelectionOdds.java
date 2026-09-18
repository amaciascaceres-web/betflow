package com.alejandromacias.betflow.betting.odds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Betting's local copy of a selection's current price.
 *
 * <p>Deliberately not called {@code Selection}: sportsbook-service owns that concept and owns the
 * price (ADR-002). This is a projection built from the event stream, kept so that reading the
 * book is cheap. The check that protects a bet — the one that decides whether money moves — still
 * asks sportsbook directly, and is allowed to disagree with this table.
 *
 * <p>There is exactly one row per selection, which is the whole reason this service needs no
 * deduplication table: applying an event replaces a value instead of accumulating one. See
 * ADR-004.
 *
 * <p>Reading a row is {@link SelectionOddsDto}'s job, not this type's. What keeps the two apart is
 * the repository's surface — no method hands back an entity — rather than anything missing here,
 * so the accessors below stay: the only instance anyone can hold is one they built a line earlier,
 * and there is nothing stale to read off it. They earn their place in tests, where asserting on a
 * row that was written needs some way to look at it.
 *
 * <p>Hibernate itself never calls them. The mapping annotations sit on the fields, so the access
 * type is field access and the values go in and out directly.
 */
@Entity
@Table(name = "selection_odds")
public class SelectionOdds {

    @Id
    @Column(name = "selection_id")
    private UUID selectionId;

    @Column(name = "market_id", nullable = false)
    private UUID marketId;

    @Column(nullable = false)
    private BigDecimal odds;

    /**
     * The timestamp carried by the event that produced this row, not the moment it was written.
     * It is what lets a later event be recognised as older, which is how the projection stays
     * correct if the stream's ordering guarantee ever lapses.
     */
    @Column(name = "odds_updated_at", nullable = false)
    private Instant oddsUpdatedAt;

    protected SelectionOdds() { }

    SelectionOdds(UUID selectionId, UUID marketId, BigDecimal odds, Instant oddsUpdatedAt) {
        this.selectionId = selectionId;
        this.marketId = marketId;
        this.odds = odds;
        this.oddsUpdatedAt = oddsUpdatedAt;
    }

    public UUID getSelectionId() { return selectionId; }
    public UUID getMarketId() { return marketId; }
    public BigDecimal getOdds() { return odds; }
    public Instant getOddsUpdatedAt() { return oddsUpdatedAt; }
}
