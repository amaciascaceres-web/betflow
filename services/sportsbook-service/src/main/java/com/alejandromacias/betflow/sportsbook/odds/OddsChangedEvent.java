package com.alejandromacias.betflow.sportsbook.odds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A fact: the price on a selection changed. This is sportsbook-service's published language —
 * whoever consumes it depends on this shape, so it changes only deliberately.
 *
 * @param eventId   generated here, when the event is built. It is the deduplication key for
 *                  consumers. Deliberately not the Kafka offset, which does not survive
 *                  repartitioning.
 * @param odds      always {@link BigDecimal}. A price ends up multiplying a stake, and binary
 *                  floating point cannot represent 2.35 exactly.
 */
public record OddsChangedEvent(
        UUID eventId,
        UUID marketId,
        UUID selectionId,
        BigDecimal odds,
        Instant timestamp) {

    public static OddsChangedEvent of(UUID marketId, UUID selectionId, BigDecimal odds) {
        return new OddsChangedEvent(UUID.randomUUID(), marketId, selectionId, odds, Instant.now());
    }
}
