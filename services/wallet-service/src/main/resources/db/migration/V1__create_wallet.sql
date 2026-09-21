-- Wallet is the context day 1 separated for its transactional consistency, and this schema is
-- where that shows: a balance nobody may overdraw, and an immutable history it must agree with.

CREATE TABLE wallet (
    id      UUID PRIMARY KEY,
    user_id UUID           NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    -- Optimistic locking. Every update carries 'AND version = ?', so two transactions that read
    -- the same balance cannot both write: the second finds no row and is told to look again.
    version BIGINT         NOT NULL
);

CREATE UNIQUE INDEX idx_wallet_user ON wallet (user_id);

-- Append-only. A balance is derived, not asserted: the sum of these entries equals wallet.balance
-- at all times, which is what makes the number auditable instead of merely current.
--
-- Amounts are signed, so that sum is a plain SUM: TOPUP and RELEASE add, RESERVATION subtracts,
-- and CONFIRMATION is zero because the money already left when it was reserved.
CREATE TABLE ledger_entry (
    id               UUID PRIMARY KEY,
    wallet_id        UUID           NOT NULL REFERENCES wallet (id),
    type             VARCHAR(20)    NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    bet_reference_id UUID
);

CREATE INDEX idx_ledger_entry_wallet ON ledger_entry (wallet_id);

-- Reserved is not spent: the money is out of reach for another bet, but the spend is only sealed
-- when the bet is confirmed, or handed back when it fails.
CREATE TABLE funds_reservation (
    id        UUID PRIMARY KEY,
    wallet_id UUID           NOT NULL REFERENCES wallet (id),
    -- A bet reserves funds once. Stated as a constraint rather than as a convention, so a
    -- request that arrives twice cannot take the money twice however it arrives.
    bet_id    UUID           NOT NULL UNIQUE,
    amount    NUMERIC(19, 2) NOT NULL,
    status    VARCHAR(20)    NOT NULL
);
