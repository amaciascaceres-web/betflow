package com.alejandromacias.betflow.wallet.funds;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Writes go in as an entity, reads come back as a value, and nothing hands out something both
 * writable and stale. Same split as ADR-004, and the status field is why it matters here: it is
 * changed by the statement below, underneath the persistence context.
 */
public interface FundsReservationRepository extends Repository<FundsReservation, UUID> {

    FundsReservation save(FundsReservation reservation);

    @Query("""
            SELECT new com.alejandromacias.betflow.wallet.funds.FundsReservationDto(
                       r.id, r.walletId, r.betId, r.amount, r.status)
              FROM FundsReservation r
             WHERE r.id = :reservationId
            """)
    Optional<FundsReservationDto> findViewById(@Param("reservationId") UUID reservationId);

    /**
     * Moves the reservation only from the state it expects, and reports whether it moved.
     *
     * <p>The guard is what makes confirming twice safe: the second call matches no row, writes
     * nothing, and the caller can tell "done now" from "already done" without reading first.
     * Reading the status, deciding in Java and writing it back would open a window where two
     * callers both see PENDING.
     *
     * <p>Zero is ambiguous, as it always is with this technique — already confirmed, already
     * released, or no such reservation — so the caller asks a second question to find out which.
     */
    @Modifying
    @Query("""
            UPDATE FundsReservation r
               SET r.status = :target
             WHERE r.id = :reservationId
               AND r.status = :expected
            """)
    int transition(@Param("reservationId") UUID reservationId,
                   @Param("expected") FundsReservationStatus expected,
                   @Param("target") FundsReservationStatus target);
}
