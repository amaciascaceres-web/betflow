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
| `WalletBusyException` reaches nobody | after three collisions it is thrown and no caller decides what that means | **8** | that day settles the business-vs-technical exception split, which is exactly this question |
| No ledger entry type for a reversal | a confirmation cannot be undone: the ledger is append-only, so undoing means posting the opposite entry, and no type exists for it | **8** | only needed if the product decides a confirmation is reversible. It may not be — confirming is the saga's pivot, and "a confirmed bet is honoured" is a legitimate answer |
| Orphaned `PENDING` reservations | a saga that dies between reserving and deciding leaves funds held forever; the invariant still holds, so nothing detects it | **8** conceptually, **19** in practice | it is the compensation that never runs. Day 19's chaos is what would surface it |
| `BetSagaState` records where a saga stopped, not what it produced | a stuck bet can be detected but not finished: nothing says which reservation to confirm or undo | **8** | recovery is meaningless without it, and compensation is what needs it first |
| The saga's own status transition is not guarded | nothing stops two deciders reaching opposite conclusions about one bet | **8** | there is only one decider today; the guard has nothing to exclude until compensation exists. The writes are already statements, so it is a condition to add rather than a rewrite |
| A step that leaves the question open — a timeout, a 5xx | unhandled: the bet is left `PENDING` and the saga `IN_PROGRESS`, and on a timeout the reservation may exist, holding money nothing will release | **8** | finding out whether the step happened has to come before deciding what to undo. Refusals, where an answer did arrive, are handled as of day 7 |
| No metric for contention | a wallet that became hot would start refusing legitimate operations and the first signal would be a complaint | **17** | metrics and an alert with a justified threshold; the `WalletBusyException` rate is the textbook candidate |

---

## Deliberately not scheduled

| Gap | Why it stays open |
|---|---|
| **No transactional outbox** (ADR-002) | the exposure is a lost price announcement, and prices self-heal within seconds. The outbox is the general answer and is best paid for where data does not repair itself |
| **No index on `ledger_entry.bet_reference_id`** | nothing filters by it. An index costs write amplification and storage for a read that does not exist, and could not be justified in review without pointing at the query. It arrives with the query — and note the query that would need it is the orphan sweep above, which a proper `expires_at` would not need at all |
| **Odds are rounded to two decimals in code and stored in a column that allows three** | `Selection.changeOddsTo` applies `setScale(2)`, the column is `NUMERIC(8,3)`, and nothing reconciles them. How many decimals a price is quoted to is a business rule; here it lives in a private constant, is stricter than the schema, and binds only writes that go through that one method. Anything writing the column directly can store three. Due the next time sportsbook is opened, together with the point below, so the schema and the code state the same rule |
| **`Selection.changeOddsTo` is the last write in the project done by mutating an entity** | Betting's projection, wallet's transitions and the saga's state are all explicit statements; this one is not, and it was defended on day 4 with an argument — that "set to X" rather than "add a delta" is what makes the event idempotent — that is still true and does not actually require a method on the entity. A statement is equally "set to X". It is inconsistency rather than risk, which is why it waits rather than being fixed across a day boundary |
| **Nothing enforces additive-only schema changes** (ADR-003) | a renamed field arrives as `null`, silently. Enforcing it means a schema registry with compatibility checks — a real answer to a real risk, and a larger commitment than this project has argued for |
| **The odds simulator moves selections independently** (ADR-002) | a real book reprices a market as a set. Modelling that correctly is an odds engine, not an event-mechanics exercise |
| **The bet request carries the `walletId`** (ADR-006) | resolving which wallet a user pays from is wallet's business, and nothing has asked for that lookup. A real caller would not know it |
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
