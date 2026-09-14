# BetCorp — design response

The answer to the four questions the business asked before any implementation detail was
allowed. Written against the business brief alone: no technology is named anywhere in this
document. Transports, frameworks and storage engines are chosen later, each with its own ADR.

---

## 1. Which services, and why each boundary is justified

Six contexts. Each boundary is argued with at least one of three criteria — **transactional
consistency** (must these change atomically together?), **rate of change** (do they evolve at
the same pace?), **data ownership** (who is the single writing authority?). Full reasoning per
context lives in [ADR-001](adr/ADR-001-bounded-contexts.md); the summary:

| Context | One-sentence responsibility | Boundary justified by |
|---|---|---|
| **Identity** | Who a user is and what they may do. | Ownership: sole writer of credentials. Rate of change: follows the account lifecycle, nothing else. |
| **Wallet** | The single authority over a user's money and its history. | Consistency: balance and its ledger entry must move atomically. Ownership: nobody else writes a balance. |
| **Sportsbook** | What can be bet on, and at what odds, right now. | Rate of change: the catalogue and the pricing rules evolve on a trading schedule of their own. Ownership: single source of truth for the current price. *Also scales independently — it absorbs far more traffic than anything else.* |
| **Betting** | Accept or reject bets, and coordinate what that takes. | Rate of change: the most frequently modified rules in the system; they must not drag the money code with them. |
| **Settlement** | Once a market resolves, decide what each bet pays. | Consistency: "paid exactly once" is a uniqueness constraint, and a constraint needs a single database to live in. Rate of change: payout rules evolve separately from bet-acceptance rules. *Also scales independently — rare massive bursts, the opposite profile to taking bets.* |
| **Notification** | Tell the user what happened to their bet. | Consistency: the business states a notification failure must not stop a settlement. That is a boundary, not a detail. |

**The boundary worth defending: Wallet apart from Betting.** They are coupled in almost every
flow, which is exactly why the split gets challenged. It holds because money needs a consistency
and audit boundary that betting logic does not, because betting rules change far more often than
balance rules, and because only Wallet may write a balance — Betting *asks*, it never performs.
Merge them and you couple releases, blur the money trail with non-financial logic, and start
building a distributed monolith.

**What was deliberately not done:** one CRUD service per table. That is a monolithic database
spread over a network — every cost of being distributed, none of the benefits. Each context here
is justified by a rule it protects, not by a table it holds.

---

## 2. Which data belongs to each

| Context | Entity | Main attributes |
|---|---|---|
| Identity | `User` | id, email, passwordHash, createdAt |
| | `Role` | id, name |
| Wallet | `Wallet` | id, userId, balance, version |
| | `LedgerEntry` | id, walletId, type (reservation / confirmation / release / top-up), amount, timestamp, betReferenceId |
| | `FundsReservation` | id, walletId, betId, amount, status (pending / confirmed / released) |
| Sportsbook | `SportEvent` | id, name, startDate, status (scheduled / in play / finished) |
| | `Market` | id, sportEventId, type, status (open / closed / resolved) |
| | `Selection` | id, marketId, name, currentOdds, oddsUpdatedAt |
| Betting | `Bet` | id, userId, marketId, selectionId, amount, appliedOdds, status |
| | `BetSagaState` | id, betId, currentStep, status (in progress / completed / failed) |
| Settlement | `SettlementBatch` | id, marketId, processedAt, totalBets |
| | `Payout` | id, betId (unique), amount, status |
| Notification | `NotificationLog` | id, userId, type, channel, status, sentAt |

Two modelling choices carry most of the weight:

- **The balance is derived, not stored as the truth.** `LedgerEntry` is an append-only history of
  movements; `Wallet.balance` is a maintained projection of it. The business asked for a complete,
  auditable history years after the fact, and for a balance that is never wrong — a single mutable
  number gives you neither. If the two ever disagree, the ledger wins.
- **A reservation is its own thing, not a subtraction.** The brief is explicit: money on a pending
  bet must not be spendable twice, but must not count as spent either. That is a third state, so
  it gets an entity with a lifecycle rather than being squeezed into the balance.

---

## 3. What talks to what, and in what form

Three kinds of interaction, distinguished by the questions the brief asks: is this a fact others
care about, an order aimed at someone, and does it need an answer now?

| From → to | What | Kind | Needs an answer now? |
|---|---|---|---|
| Sportsbook → anyone interested | The odds on a selection changed | **Fact** | No. Fire and forget. |
| Sportsbook → anyone interested | A market resolved with a result | **Fact** | No, but it must not be lost. |
| Betting → anyone interested | A bet was placed | **Fact** | No. |
| Settlement → anyone interested | A bet was settled | **Fact** | No. |
| Betting → Sportsbook | Are these odds still current? | **Query** | **Yes.** The bet cannot be accepted without it. |
| Betting → Wallet | Reserve these funds | **Command** | No — but the outcome must come back and must not be lost. |
| Settlement → Wallet | Apply this payout | **Command** | No. |
| Betting → Wallet | Release these funds (compensation) | **Command** | No. Must be safe to repeat. |

**Only one interaction genuinely blocks.** Checking that the odds are still current has to happen
before the bet is accepted — that is a business rule from the brief, not a technical preference.
Everything else can be resolved later without the user waiting, which is what lets the system keep
taking bets while parts of it are degraded.

**Facts and commands are not the same thing and should not be treated as one.** A fact has no
intended recipient: Sportsbook must not know or care that Betting, Analytics and Audit all
listen, and adding a fourth listener must require no change to Sportsbook. A command has exactly
one recipient and the sender cares whether it ran. They also need different delivery behaviour —
a fact benefits from being replayable by a consumer that joins late or falls behind; a command
needs retries and somewhere to park the ones that keep failing. That difference is the reason to
expect two mechanisms rather than one, but the choice belongs in its own decision record.

**No context reads another's data store.** Communication is through published facts or a
published interface, never a shared table. This is what makes the boundaries real instead of
cosmetic.

---

## 4. Consistency decisions

The general rule: **strong consistency inside a boundary, eventual consistency across
boundaries, and an explicit compensation wherever "eventual" is not good enough on its own.**

### Strong, non-negotiable

- **Inside Wallet.** Balance, reservation and ledger entry change atomically or not at all.
  Money cannot be eventually consistent: a briefly wrong balance means either letting a user
  overspend or blocking a legitimate bet, and the brief calls any discrepancy unacceptable.
- **Concurrent writes to one wallet.** Two bets placed at the same moment must not both pass a
  "you can afford it" check against the same balance. The writes have to be serialised, and the
  loser has to be told, not silently dropped.
- **Inside Betting.** A bet and the state of its coordination move together.
- **Settlement paying out.** "Never pay the same winning bet twice, never miss one that won" is
  stated as absolute. This is enforced by making a payout unique per bet — a guarantee that holds
  no matter how many times the settlement is re-run, which is stronger and simpler than trying to
  coordinate a transaction across services.

### Eventual, deliberately

- **The odds a user is looking at.** Always slightly behind reality; this is unavoidable and fine
  for *displaying* them. It is not fine for *accepting a bet* on them — which is precisely why
  the price is re-checked at the moment of confirmation. Eventual on the read path, verified on
  the write path.
- **Between Betting and Wallet.** There is no transaction spanning both. The bet is not confirmed
  until the reservation comes back. In the window between, the money is reserved rather than
  lost, and if the flow fails the reservation is released by a compensating operation. Being a
  new operation rather than a rollback, it must be safe to run more than once.
- **Settlement reacting to a resolved market.** Thousands of bets do not have to settle in one
  instant, they have to settle correctly. Spreading the work out is acceptable; a partial failure
  mid-batch must be resumable without double-paying.
- **Notifications.** The brief makes this explicit: a notification failure must not affect
  settlement or payment. Eventual, and allowed to fail outright.
- **Compliance reporting and activity analysis.** The business wants these queries not to affect
  platform performance, which means they read from somewhere other than the live write path and
  are therefore behind by construction.
- **Any read model built for the live odds board.** Stale is acceptable; the source of truth
  stays where the writes are. If the process keeping it up to date stops, the view goes stale but
  no data is lost.

### One "eventual" that is not allowed to lose anything

The **immutable audit record** is eventually consistent in timing but not in completeness. The
brief requires a complete, unalterable record for external audits. Analytics may tolerate a
dropped sample for a dashboard; the audit trail may not drop anything, ever, and may never be
updated or deleted after the fact. Same delivery style, different guarantee — worth separating so
the weaker requirement never sets the standard for the stronger one.

### The cost being accepted

Eventual consistency across boundaries buys independent deployment, independent scaling and
survival of partial failures — all of which the brief asks for. It is paid for in explicit
compensations, in operations that must be safe to repeat, and in flows whose state has to be
stored rather than held in memory. Those are not accidents of the design; they are the design.
