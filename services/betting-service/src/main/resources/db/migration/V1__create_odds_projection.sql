-- Betting's local copy of the current price, fed by OddsChanged.
--
-- It is a projection, not the source of truth: sportsbook-service owns the price (ADR-002).
-- This table exists so that reads are cheap; the validation that protects a bet still asks
-- sportsbook directly.
--
-- One row per selection, so applying an event is a replacement rather than an accumulation.
-- That is what makes reprocessing harmless, and it is why there is no deduplication table
-- alongside it. See ADR-004.
CREATE TABLE selection_odds (
    selection_id    UUID PRIMARY KEY,
    market_id       UUID          NOT NULL,
    odds            NUMERIC(8, 3) NOT NULL,
    odds_updated_at TIMESTAMPTZ   NOT NULL
);

-- Betting reads the book market by market, never selection by selection.
CREATE INDEX idx_selection_odds_market ON selection_odds (market_id);
