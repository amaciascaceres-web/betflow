package com.alejandromacias.betflow.betting.odds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Betting's own reading of the {@code OddsChanged} fact published by sportsbook-service.
 *
 * <p>It is a separate declaration on purpose, not an import and not a shared DTO module. A shared
 * types artifact would put two services on one release train: a field renamed for one consumer's
 * convenience recompiles every other consumer, and the producer can no longer tell who depends on
 * what. What is actually shared is the JSON on the topic — this record is this context's mapping
 * of it, and it is free to diverge (drop a field it does not use, rename one locally) as long as
 * it can still read what arrives.
 *
 * <p>The duplication is the price of that independence, and it is the smaller cost: the shape is
 * fixed by a published contract, so it changes rarely and deliberately.
 */
public record OddsChangedEvent(
        UUID eventId,
        UUID marketId,
        UUID selectionId,
        BigDecimal odds,
        Instant timestamp) {
}
