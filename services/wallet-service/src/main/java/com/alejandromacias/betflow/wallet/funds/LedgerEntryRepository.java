package com.alejandromacias.betflow.wallet.funds;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Append and read, and the read gives back values.
 *
 * <p>There is no update and no delete, and that is the whole design: an append-only ledger
 * enforced by the methods that exist rather than by a rule someone has to remember. Extending
 * {@code JpaRepository} would hand back {@code delete}, {@code deleteAll} and {@code saveAll}
 * without anyone deciding to — and returning entities would hand back rows that dirty checking
 * could rewrite on the way out.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    LedgerEntry save(LedgerEntry entry);

    @Query("""
            SELECT new com.alejandromacias.betflow.wallet.funds.LedgerEntryDto(
                       e.id, e.walletId, e.type, e.amount, e.timestamp, e.betReferenceId)
              FROM LedgerEntry e
             WHERE e.walletId = :walletId
             ORDER BY e.timestamp ASC
            """)
    List<LedgerEntryDto> findViewsByWalletId(@Param("walletId") UUID walletId);
}
