package com.alejandromacias.betflow.betting.odds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a reader gets back: a value, built by the query itself.
 *
 * <p>Not a mapping of {@link SelectionOdds} performed afterwards — that distinction is the whole
 * point. A query asking for entities hands back whatever instance the persistence context already
 * holds, discarding the row it just read; a query that constructs this reads the row. The
 * difference shows up the moment anything writes without going through Hibernate, which is what
 * the conditional update does.
 *
 * <p>It also makes an accidental write impossible. A managed entity that someone adjusts in
 * passing is flushed at commit as an {@code UPDATE} nobody asked for and nobody can grep for.
 * There is nothing to adjust here.
 *
 * <p>Named {@code Dto} rather than {@code View}: inside a persistence package the latter reads as
 * a database view, and the suffix is carrying recognition rather than meaning. That it is
 * read-only and query-built is what this comment is for.
 */
public record SelectionOddsDto(
        UUID selectionId,
        UUID marketId,
        BigDecimal odds,
        Instant oddsUpdatedAt) {
}
