package com.alejandromacias.betflow.wallet.funds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The available balance, and the rule that protects it.
 *
 * <p>Unlike betting's projection, this is an entity with behaviour, because there is an invariant
 * to defend: nobody may reserve more than is there. Its place is next to the field, not in a
 * service that could be bypassed.
 */
@Entity
@Table(name = "wallet")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private BigDecimal balance;

    /**
     * Optimistic locking, and the reason this service needs no row locks. Hibernate appends
     * {@code AND version = ?} to every update and counts the rows it changed: two transactions
     * that read the same balance cannot both write, because the second matches nothing and is
     * told so.
     *
     * <p>It guards the whole row rather than one column, which is the argument for preferring it
     * to a conditional update here — the fields this aggregate grows are covered without anyone
     * remembering to cover them.
     */
    @Version
    private Long version;

    protected Wallet() { }

    public Wallet(UUID id, UUID userId, BigDecimal balance) {
        this.id = id;
        this.userId = userId;
        this.balance = balance;
    }

    /**
     * Takes the amount out of the available balance, or refuses. Refusing is a business answer
     * and not a failure: it is the correct outcome of asking for more than there is, and
     * nothing up the stack should retry it.
     */
    void reserve(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        balance = balance.subtract(amount);
    }

    /** Hands an unspent hold back. Only ever called for a reservation that was still PENDING. */
    void release(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }
}
