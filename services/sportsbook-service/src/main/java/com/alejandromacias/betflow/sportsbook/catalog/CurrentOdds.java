package com.alejandromacias.betflow.sportsbook.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The authoritative answer to "what is this selection priced at right now".
 *
 * <p>A value built by the query, not the {@link Selection} entity: this is a read, and the entity
 * is the write model the odds simulator mutates. Same split as ADR-004.
 */
public record CurrentOdds(
        UUID selectionId,
        UUID marketId,
        BigDecimal odds,
        Instant oddsUpdatedAt) {
}
