-- Placing a bet crosses two other services, so it cannot be one transaction. What replaces the
-- transaction is these two tables: the bet, and a record of how far its saga got.

CREATE TABLE bet (
    id           UUID PRIMARY KEY,
    user_id      UUID           NOT NULL,
    market_id    UUID           NOT NULL,
    selection_id UUID           NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    -- Unknown until the price has been checked, so the row exists before it can be filled.
    applied_odds NUMERIC(8, 3),
    status       VARCHAR(20)    NOT NULL
);

CREATE INDEX idx_bet_user ON bet (user_id);

-- Written before each step rather than after the last one. A saga that only records its outcome
-- can say a bet failed; one that records where it got to can say what is still owed.
CREATE TABLE bet_saga_state (
    id           UUID PRIMARY KEY,
    -- One saga per bet. The same reasoning as UNIQUE(bet_id) in wallet: an invariant the database
    -- can hold is worth more than one every caller has to remember.
    bet_id       UUID        NOT NULL UNIQUE REFERENCES bet (id),
    current_step VARCHAR(30) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    -- Not in the original model, and added on the strength of day 6's lesson: a state row with no
    -- age cannot be swept. Wallet's reservations have that problem today — they can be listed but
    -- not distinguished from legitimate ones in flight — and repeating it here would be choosing
    -- to make the same mistake twice.
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bet_saga_state_status ON bet_saga_state (status, updated_at);
