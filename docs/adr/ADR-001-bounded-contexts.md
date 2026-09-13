# ADR-001 — Choosing the bounded contexts

- **Status:** accepted
- **Date:** day 1
- **Context:** the BetCorp business brief (online sports betting platform, high traffic peaks,
  real money, regulated sector). The service boundaries have to be fixed before any business
  logic is written, because every later decision depends on them.

## The three criteria used to draw each boundary

1. **Transactional consistency** — if two concepts almost always have to change atomically
   together, they belong to the same context.
2. **Rate of change** — do they evolve independently, or always at the same time?
3. **Data ownership** — who is the *single* writing authority for that data?

Every context below cites at least one of them. "Because it seems reasonable" does not count.

Criterion 2 is about how fast the **code** evolves, not how fast the **data** moves or how much
load arrives. Those are real arguments too, but they are arguments for **independent
scalability**, which is recorded separately below rather than folded into criterion 2 —
stretching a criterion to cover something it does not say is how an ADR stops being checkable.

## Decision: six bounded contexts

### identity-service — port 8081, schema `identity`
- **Responsibility:** be the authority on who a user is and what they are allowed to do.
- **Entities it owns:** `User(id, email, passwordHash, createdAt)`, `Role(id, name)`.
- **Criteria:** (2) rate of change — identity changes with the account lifecycle, not with the
  sports catalogue or with betting rules; (3) ownership — it is the only writer of credentials,
  and no other context should be able to touch them.

### wallet-service — port 8082, schema `wallet`
- **Responsibility:** be the single authority over a user's money and its history.
- **Entities it owns:** `Wallet(id, userId, balance, version)`,
  `LedgerEntry(id, walletId, type, amount, timestamp, betReferenceId)`,
  `FundsReservation(id, walletId, betId, amount, status)`.
- **Criteria:** (1) transactional consistency — the balance and its ledger entry must change
  atomically or the balance stops being auditable; (3) ownership — nobody else writes `balance`,
  not even Betting, which only *asks* for a reservation.

### sportsbook-service — port 8083, schema `sportsbook`
- **Responsibility:** be the authority on what can be bet on, and at what odds, right now.
- **Entities it owns:** `SportEvent(id, name, startDate, status)`,
  `Market(id, sportEventId, type, status)`, `Selection(id, marketId, name)`.
  Publishes `OddsChanged` (a fact it makes public, not a table).
- **Criteria:** (2) rate of change — the catalogue of sports, the market types on offer and the
  pricing rules evolve on a trading schedule driven by external data providers, unrelated to when
  betting rules or balance rules change; (3) ownership — the current odds have a single source of
  truth, and the rest of the system learns about them through its published fact.
- **Also, separately — independent scalability:** odds change several times per second while
  almost nothing else does. That volume has to be able to scale on its own, without dragging
  along contexts that see a fraction of the traffic.

### betting-service — port 8084, schema `betting`
- **Responsibility:** accept or reject bets, and coordinate the steps that requires.
- **Entities it owns:** `Bet(id, userId, marketId, selectionId, amount, appliedOdds, status)`,
  `BetSagaState(id, betId, currentStep, status)`.
- **Criteria:** (2) rate of change — betting rules (new bet types, accumulators, limits) are the
  most frequently touched part of the system, and they must not drag the money code along;
  (3) ownership — it owns the bet's state and its saga's state, but not the money.

### settlement-service — port 8085, schema `settlement`
- **Responsibility:** decide, once a market resolves, how much each bet pays out.
- **Entities it owns:** `SettlementBatch(id, marketId, processedAt, totalBets)`,
  `Payout(id, betId UNIQUE, amount, status)`.
- **Criteria:** (1) transactional consistency — "a bet is paid exactly once" is enforced by a
  uniqueness constraint on the payout, and a constraint only exists inside one database. Its own
  boundary is what makes the strongest guarantee in the system cost one line of DDL instead of a
  distributed lock; (2) rate of change — payout rules (new bet types, voided markets, pushes that
  return the stake, partial cashout) evolve for different reasons, and at different moments, than
  the rules for *accepting* a bet.
- **Also, separately — independent scalability:** taking bets is continuous, small and
  latency-sensitive, because a user is waiting for the answer. Settling is silent for ninety
  minutes and then tens of thousands of bets at once, with nobody watching a spinner. In one
  deployable they would share a connection pool and a thread pool, so a settlement burst would
  degrade bet acceptance directly, and neither could be scaled without the other.

### notification-service — port 8086, schema `notification`
- **Responsibility:** tell the user what happened to their bet.
- **Entities it owns:** `NotificationLog(id, userId, type, channel, status, sentAt)`.
- **Criteria:** (1) consistency — the business states explicitly that a notification failure must
  **not** prevent a bet from being settled and paid; that is a consistency boundary, not an
  implementation detail; (2) rate of change — it depends on external providers (email, push)
  that change for reasons unrelated to the betting domain.

## Why Wallet and Betting are separate, despite being tightly coupled in the flow

This is the day's heavyweight decision and the one most often challenged in an interview, because
Betting calls Wallet in nearly every operation. They are still separated:

- **Their own consistency and audit needs:** money demands a transactional boundary and an
  immutable record that betting logic does not need.
- **Different rate of change:** betting rules change far more often than balance rules.
- **Different writing authority:** only Wallet modifies `balance`; Betting *orchestrates* the
  reservation, it never performs it itself.

**What breaks if they are merged:** releases become coupled (touching betting logic forces a
re-test of the money code), clean traceability of financial movements is lost by mixing it with
non-financial logic, and the system drifts towards a distributed monolith that cannot be scaled
or deployed in parts.

## Communication rules between contexts

- **Forbidden:** direct access to another context's database. A service only communicates through
  the other's public API or the events it publishes (its *published language*).
- Locally every schema lives in the same Postgres container. That is a convenience, not a licence:
  the rule above still stands, and it is what makes the separation real rather than cosmetic.
- Interactions are classified by what they are, not by what will carry them. **A fact several
  parties care about** (`OddsChanged`, `BetPlaced`, `BetSettled`, `MarketResolved`); **an order
  aimed at one recipient** (`ReserveFundsCommand`); **a question that blocks until answered**
  (is this price still current?). Facts and commands need different delivery behaviour, so they
  will probably not share one mechanism — but choosing those mechanisms is a separate decision
  with its own record.

## Consequences

- Each service deploys and scales on its own; shutting one down must not take the others with it
  (verified today through the six independent `/actuator/health` endpoints).
- The price is eventual consistency between contexts and the need for explicit compensations:
  there is no distributed transaction covering "validate odds + reserve funds + confirm bet".
  Hence the Saga on day 7.
- A change spanning two contexts costs more than it would in a monolith. That is the cost
  knowingly accepted in exchange for deployment independence and a hard boundary around the money.

## Anti-pattern deliberately avoided

**"Microservice = table".** Exposing one CRUD entity per service is not domain design: it is a
monolithic database spread over the network, without encapsulated logic, carrying every cost of
being distributed and none of the benefits. Here each context is justified by a business rule it
protects, not by a table it contains.

## Control question

*If tomorrow the business asked for "accumulator bets across several markets", which context
would be affected?*

**betting-service, and only it.** An accumulator is a bet whose stake depends on several
`Selection`s and whose resulting odds are the product of the individual ones: that changes the
shape of `Bet` and the steps of its saga. Sportsbook keeps publishing exactly the same
`OddsChanged` (an accumulator creates no new markets, it references existing ones). Wallet keeps
reserving an amount against a `betId` and does not care how many selections it came from.
Settlement would need to know that an accumulator only wins if all its legs win — but that is a
calculation rule inside `PayoutCalculator`, not a new boundary.

Being able to answer that straight from the ADR is the signal that the boundaries are well drawn.
