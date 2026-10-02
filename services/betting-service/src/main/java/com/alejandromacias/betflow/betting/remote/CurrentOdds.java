package com.alejandromacias.betflow.betting.remote;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sportsbook's answer to "what is this priced at now". Betting's own declaration of the shape,
 * not an import — the same reasoning as the event contract in ADR-003, and the same reason:
 * sharing a type would make one service's refactor the other's outage.
 */
public record CurrentOdds(UUID selectionId, UUID marketId, BigDecimal odds) {
}
