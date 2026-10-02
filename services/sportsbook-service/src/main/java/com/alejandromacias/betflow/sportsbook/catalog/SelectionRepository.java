package com.alejandromacias.betflow.sportsbook.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SelectionRepository extends JpaRepository<Selection, UUID> {

    List<Selection> findByMarketId(UUID marketId);

    /**
     * The price this service is the authority on, for whoever needs to check one before
     * committing to it.
     *
     * <p>Both ids are in the condition on purpose. A selection that does not belong to the market
     * the caller names is a caller mistake, and answering it anyway would let a bet be placed
     * against a pairing that does not exist. Getting nothing back says so without a second query.
     */
    @Query("""
            SELECT new com.alejandromacias.betflow.sportsbook.catalog.CurrentOdds(
                       s.id, s.marketId, s.currentOdds, s.oddsUpdatedAt)
              FROM Selection s
             WHERE s.id = :selectionId
               AND s.marketId = :marketId
            """)
    Optional<CurrentOdds> findCurrentOdds(@Param("selectionId") UUID selectionId,
                                          @Param("marketId") UUID marketId);
}
