package com.alejandromacias.betflow.betting.odds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends {@link Repository} rather than {@code JpaRepository}, so the only methods that exist are
 * the ones below. That is the point: nothing can ask for a managed {@link SelectionOdds} to read
 * from, because no such method is declared. Reads come back as {@link SelectionOddsDto}, writes
 * go in as an entity, and the split is enforced by the compiler instead of by remembering it.
 */
public interface SelectionOddsRepository extends Repository<SelectionOdds, UUID> {

    /** Cheap enough to be worth its own question: resolves the ambiguity of an update that wrote nothing. */
    boolean existsById(UUID selectionId);

    /** The write side. The entity exists to be persisted, never to be read from. */
    SelectionOdds save(SelectionOdds selectionOdds);

    /**
     * Betting reads the book a market at a time, which is what the index on market_id is for.
     *
     * <p>The constructor expression is deliberate, not decoration: it is what makes this a read
     * rather than a handle on something writable. See {@link SelectionOddsDto}.
     */
    @Query("""
            SELECT new com.alejandromacias.betflow.betting.odds.SelectionOddsDto(
                       s.selectionId, s.marketId, s.odds, s.oddsUpdatedAt)
              FROM SelectionOdds s
             WHERE s.marketId = :marketId
            """)
    List<SelectionOddsDto> findViewsByMarketId(@Param("marketId") UUID marketId);

    @Query("""
            SELECT new com.alejandromacias.betflow.betting.odds.SelectionOddsDto(
                       s.selectionId, s.marketId, s.odds, s.oddsUpdatedAt)
              FROM SelectionOdds s
             WHERE s.selectionId = :selectionId
            """)
    Optional<SelectionOddsDto> findViewBySelectionId(@Param("selectionId") UUID selectionId);

    /**
     * Writes the price if — and only if — the incoming event is newer than the row already
     * stored. Returns the number of rows written, so the caller can tell an applied change from
     * one that was correctly ignored.
     *
     * <p>The comparison is part of the statement rather than a decision taken in Java between a
     * read and a write. The database evaluates it while holding the row, so two writers cannot
     * both read the old value and both conclude they are newer. Reading the row first and
     * comparing here would reintroduce exactly that lost update.
     *
     * <p>Two things fall out of expressing it this way:
     *
     * <ul>
     *   <li><b>Duplicates cost nothing.</b> A redelivered event carries the same timestamp, so
     *       {@code <} is false and no row is written at all. The result would be correct without
     *       the condition — it would rewrite identical values — but it would pay for it.
     *   <li><b>Order stops mattering.</b> An older event cannot overwrite a newer one. Kafka
     *       already orders records within a partition, so this should never trigger; ADR-002
     *       records that repartitioning breaks exactly that guarantee, and this is what the
     *       projection would survive on that day.
     * </ul>
     *
     * <p>{@code <} rather than {@code <=}: two distinct events sharing a timestamp would see the
     * second ignored. At microsecond resolution that is unlikely, and preferring {@code <=} would
     * trade it for a write on every duplicate.
     *
     * <p>Zero rows is ambiguous — the row may be absent, or it may simply be newer — so the
     * caller has to distinguish. That ambiguity is the price of staying inside JPQL, which has no
     * upsert; see ADR-004.
     */
    @Modifying
    @Query("""
            UPDATE SelectionOdds s
               SET s.odds = :odds,
                   s.oddsUpdatedAt = :oddsUpdatedAt
             WHERE s.selectionId = :selectionId
               AND s.oddsUpdatedAt < :oddsUpdatedAt
            """)
    int updateIfNewer(@Param("selectionId") UUID selectionId,
                      @Param("odds") BigDecimal odds,
                      @Param("oddsUpdatedAt") Instant oddsUpdatedAt);
}
