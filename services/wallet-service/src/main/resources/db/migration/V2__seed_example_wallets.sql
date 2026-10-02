-- Example wallets so the place-a-bet flow has somewhere to take money from.
--
-- Seed data rather than a top-up feature: putting money into a wallet is a real product concern
-- with its own rules, and nothing has asked for it. Kept in its own migration so it can be
-- dropped without touching the schema the day a real funding flow exists.
--
-- Every wallet opens with a TOPUP for its full balance, so the invariant holds from the first
-- row: the sum of the ledger equals the balance, with no unexplained money.

INSERT INTO wallet (id, user_id, balance, version) VALUES
  ('bbbbbbbb-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', 500.00, 0),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'cccccccc-0000-0000-0000-000000000002', 100.00, 0),
  ('bbbbbbbb-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000003',   5.00, 0);

INSERT INTO ledger_entry (id, wallet_id, type, amount, occurred_at, bet_reference_id) VALUES
  ('dddddddd-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', 'TOPUP', 500.00, NOW(), NULL),
  ('dddddddd-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002', 'TOPUP', 100.00, NOW(), NULL),
  ('dddddddd-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000003', 'TOPUP',   5.00, NOW(), NULL);

-- The third wallet holds 5.00 on purpose: a balance too small for any realistic stake, so the
-- refusal path can be exercised by hand without editing data first.
