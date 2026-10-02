package com.alejandromacias.betflow.betting.bet;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BetRepository extends Repository<Bet, UUID> {

    Bet save(Bet bet);

    Optional<Bet> findById(UUID id);

    /** The price settles at the first step; whether the bet is accepted is decided at the last. */
    @Modifying
    @Query("UPDATE Bet b SET b.appliedOdds = :odds WHERE b.id = :betId")
    int recordAppliedOdds(@Param("betId") UUID betId, @Param("odds") BigDecimal odds);

    @Modifying
    @Query("UPDATE Bet b SET b.status = :status WHERE b.id = :betId")
    int settle(@Param("betId") UUID betId, @Param("status") BetStatus status);
}
