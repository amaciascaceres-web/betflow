-- The sportsbook catalogue: what can be bet on, and at what price right now.

CREATE TABLE sport_event (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    start_date  TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(20)  NOT NULL
);

CREATE TABLE market (
    id             UUID        PRIMARY KEY,
    sport_event_id UUID        NOT NULL REFERENCES sport_event (id),
    type           VARCHAR(50) NOT NULL,
    status         VARCHAR(20) NOT NULL
);

CREATE INDEX idx_market_sport_event ON market (sport_event_id);

CREATE TABLE selection (
    id                UUID          PRIMARY KEY,
    market_id         UUID          NOT NULL REFERENCES market (id),
    name              VARCHAR(100)  NOT NULL,
    -- The current price lives here because sportsbook-service is the authority on it: it has to
    -- answer "is this price still current?" synchronously when a bet is placed. The event stream
    -- carries the changes; this column carries the answer. See ADR-002.
    current_odds      NUMERIC(8, 3) NOT NULL,
    odds_updated_at   TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_selection_market ON selection (market_id);
