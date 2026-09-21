# Known gaps

Every ADR records what it deliberately left undone, which is the right place for the reasoning
but a poor place to see the whole picture. This page is the index: what is missing, what it would
cost, and when it is due.

**This file is the one artefact that looks forward on purpose.** Everywhere else the convention
holds — an ADR describes what was decided that day and names no later day. Here the whole point
is the schedule.

A gap listed here is a decision, not an oversight. Anything genuinely unnoticed is by definition
not on this page.

---

## Due on a specific day

| Gap | Consequence today | Due | Why then |
|---|---|---|---|
| No way to create a wallet or add funds | `TOPUP` exists as a type and no operation produces one; wallet-service cannot be exercised outside its tests | **7** | the saga's happy path needs a funded wallet, so the day cannot start without it. Likely a seed migration, as in sportsbook's `V2`, rather than a top-up feature nobody asked for |
| `WalletBusyException` reaches nobody | after three collisions it is thrown and no caller decides what that means | **8** | that day settles the business-vs-technical exception split, which is exactly this question |
| No ledger entry type for a reversal | a confirmation cannot be undone: the ledger is append-only, so undoing means posting the opposite entry, and no type exists for it | **8** | only needed if the product decides a confirmation is reversible. It may not be — confirming is the saga's pivot, and "a confirmed bet is honoured" is a legitimate answer |
| Orphaned `PENDING` reservations | a saga that dies between reserving and deciding leaves funds held forever; the invariant still holds, so nothing detects it | **8** conceptually, **19** in practice | it is the compensation that never runs. Day 19's chaos is what would surface it |
| No metric for contention | a wallet that became hot would start refusing legitimate operations and the first signal would be a complaint | **17** | metrics and an alert with a justified threshold; the `WalletBusyException` rate is the textbook candidate |

---

## Deliberately not scheduled

| Gap | Why it stays open |
|---|---|
| **No transactional outbox** (ADR-002) | the exposure is a lost price announcement, and prices self-heal within seconds. The outbox is the general answer and is best paid for where data does not repair itself |
| **No index on `ledger_entry.bet_reference_id`** | nothing filters by it. An index costs write amplification and storage for a read that does not exist, and could not be justified in review without pointing at the query. It arrives with the query — and note the query that would need it is the orphan sweep above, which a proper `expires_at` would not need at all |
| **Nothing enforces additive-only schema changes** (ADR-003) | a renamed field arrives as `null`, silently. Enforcing it means a schema registry with compatibility checks — a real answer to a real risk, and a larger commitment than this project has argued for |
| **The odds simulator moves selections independently** (ADR-002) | a real book reprices a market as a set. Modelling that correctly is an odds engine, not an event-mechanics exercise |
| **`OddsChanged` carries no "this selection exists" fact** (ADR-004) | it is why betting's projection needs an insert branch at all. Inventing a catalogue event is a larger change than the current shape justifies |

---

## Assumptions that hold today and nothing enforces

These are not missing features. They are properties the code depends on, that no test and no
constraint would notice breaking.

| Assumption | What depends on it | What would break it |
|---|---|---|
| One consumer of the group owns a partition | betting's check-then-insert is atomic only because of this (ADR-004) | a second writer to `selection_odds`: an admin correction, a backfill, a migration script |
| Event timestamps are comparable across producers | the projection's last-writer-wins guard (ADR-004) | a second sportsbook instance with clock skew |
| A wallet row has few concurrent writers | optimistic locking is the right trade only under occasional contention (ADR-005) | a shared or corporate wallet, or a bot |
