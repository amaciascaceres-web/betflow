-- A single Postgres container, one schema per bounded context.
-- The isolation is logical, not physical: enough for local development, and it keeps the
-- rule that no service reads or writes another's schema (see ADR-001).
-- In production each schema would be its own database (or instance), with per-service
-- credentials.

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS wallet;
CREATE SCHEMA IF NOT EXISTS sportsbook;
CREATE SCHEMA IF NOT EXISTS betting;
CREATE SCHEMA IF NOT EXISTS settlement;
CREATE SCHEMA IF NOT EXISTS notification;

-- The betflow user already owns the database; granting explicitly makes it clear that each
-- service's migrations (Flyway, from day 4 on) run under this role.
GRANT ALL ON SCHEMA identity, wallet, sportsbook, betting, settlement, notification TO betflow;
