package com.alejandromacias.betflow.wallet.funds;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends Repository<Wallet, UUID> {

    /**
     * Returns the aggregate itself, on purpose. Reserving has to read the balance, decide, and
     * write — and the version travelling with the entity is what makes that safe. It is the one
     * method here that hands back an entity, and the only one whose caller mutates what it gets;
     * everything else on this module reads values. See ADR-004 and ADR-005.
     */
    Optional<Wallet> findById(UUID id);

    Wallet save(Wallet wallet);

    /**
     * For anyone who only wants to know the number. Reserving needs the aggregate; reading a
     * balance does not, and taking the entity for it would hand out something writable and
     * versioned for no reason.
     */
    @Query("SELECT w.balance FROM Wallet w WHERE w.id = :walletId")
    Optional<BigDecimal> findBalanceById(@Param("walletId") UUID walletId);

    /**
     * The sum the balance has to agree with. A plain SUM works because amounts are signed and
     * {@code CONFIRMATION} contributes zero.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.walletId = :walletId")
    BigDecimal ledgerTotalFor(@Param("walletId") UUID walletId);
}
