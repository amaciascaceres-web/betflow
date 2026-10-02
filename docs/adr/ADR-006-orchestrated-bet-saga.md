# ADR-006 — Placing a bet across three services: orchestration, and what replaces the transaction

- **Status:** accepted
- **Date:** day 7
- **Context:** Placing a bet is the first operation in this project that crosses bounded contexts.
  It needs the current price from sportsbook and a hold on funds from wallet, in that order, and
  neither of them can take part in a transaction owned by betting. Everything below follows from
  that one fact.

---

## Decision 1 — Orchestrated, not choreographed

**Chosen:** a single component in betting decides each step explicitly and records where it got
to.

Three properties of *this* flow ask for it:

- **Someone has to know which step a bet is on.** "Why did this bet stop halfway" is a question
  that will be asked, and it needs an answer that is not assembled by reading three services'
  logs side by side.
- **The failures need undoing in a definite order.** Compensation is coming, and it has to be
  coordinated rather than emergent.
- **The steps are few and stable.** Two calls, one order, unlikely to change. There is no open
  ecosystem of unknown participants here.

**The interesting part is not that orchestration wins — it is that the same system will choose
the other one.** Analytics and audit will consume facts without anyone orchestrating them,
because they do not need to be in charge of anything; they need to find out. The question is
never which style is better. It is whether anybody has to be in charge, and here somebody does:
money moves in a sequence that can fail partway.

**What choreography would cost here.** "What to undo, and in what order" would be spread across
services reacting to each other's failure events, with nobody holding the whole picture. The
failure mode is not a crash — it is a compensation that quietly never happens, or happens twice.

---

## Decision 2 — The saga is not a transaction, and the code has to show it

**Chosen:** the orchestrator carries no `@Transactional`. Its local writes go through a separate
bean, `BetSagaStore`, where each one commits on its own.

This is the decision most likely to be undone by someone tidying up, so the reasoning belongs
here rather than only in a comment. Wrapping `placeBet` in a transaction would read as an
improvement and would destroy the point: nothing would be visible until the end, so the only
state a failure could leave behind would be no state at all. A saga exists *because* there is no
rollback spanning the three services — the record of how far it got is the replacement, and it
has to survive each step independently.

**A separate bean, not private methods.** Spring applies transactions through a proxy, so a
method calling its own `@Transactional` method bypasses it entirely. The annotation compiles,
reads correctly, and does nothing — which is worse than not having it.

**State written before each step, not after.** A crash then leaves the row pointing at the step
that was in flight, which is the one still owed. Written afterwards, it would point at the last
thing that succeeded and say nothing about what was attempted next.

### Writes are statements, not mutations of loaded entities

JPA offers two ways to write, and the difference is only visible if you know to look for it.
Loading an entity inside a transaction and changing a field is enough: the flush compares it
against the copy it kept and emits the update by itself. Nothing in the code says "write".

Measured, because the obvious correction does not work: adding `save()` after the change produces
**byte-identical SQL**. On a managed entity that call is a merge of an instance the context
already holds, so it does nothing, and the update still comes from the flush. It reads as
explicit while changing nothing — worse than leaving it out.

The writes are therefore stated:

```java
@Modifying
@Query("UPDATE BetSagaState s SET s.currentStep = :step, s.updatedAt = :now WHERE s.betId = :betId")
int enterStep(UUID betId, SagaStep step, Instant now);
```

Three things follow. Every write is greppable. It matches how wallet and the odds projection
already write, so the project stops doing one thing two ways. And the updates narrow: dirty
checking wrote `set bet_id=?,current_step=?,status=?,updated_at=?` on every step, where the
statement writes only the two columns that changed — which matters the moment two writers touch
one row for different reasons.

**`Bet` and `BetSagaState` therefore carry no behaviour**, and that is the right answer *here*
rather than a general preference. Their methods were `status = X` with no rule to protect.
`Wallet.reserve()` keeps its behaviour precisely because it defends an invariant: the entity that
owns a rule is where the rule belongs.

**Not guarded yet.** The statements take no `WHERE status = :expected`, because there is one
decider and no second conclusion to exclude. When compensation arrives there will be, and the
guard is then a condition added to an existing `WHERE` rather than a rewrite — which is the
other reason to state the write now.

### What this state does not yet record

It says where a saga stopped. It does not say what each step produced — which reservation to
finish or undo, for instance. That is enough to detect a stuck bet and not enough to resolve one,
and the difference has to be closed before recovery is worth claiming. Recorded rather than
discovered later.

---

## Decision 3 — The check asks sportsbook, not the copy next door

Betting has had every price in `selection_odds` since day 4, maintained by the event stream and
sitting in the same database as the bet being written. The validation does not use it.

ADR-004 decision 3 committed to exactly this: the projection may be seconds out of date and that
is fine for reading a book, while a bet is a decision to move money. Today is the day that
promise is either honoured or quietly broken, and honouring it costs one synchronous call.

**Sportsbook answers "what is it priced at", not "may this bet be accepted".** Deciding whether a
price is acceptable — and how much drift to tolerate — belongs to whoever owns the bet. Keeping
the remote question that narrow is what lets the rule change without the endpoint changing.

**The comparison is exact.** Accepting drift, or accepting a price that moved in the bettor's
favour, is a product rule nobody has stated, and guessing at one would be deciding how much money
the house gives away.

---

## Decision 4 — Refusals are handled; open questions are not

**Chosen:** a step that comes back with a *no* closes the bet and the saga. A step that leaves the
question open is left for the day that can undo things.

The line is not "before the money moves" — it is **whether an answer arrived**:

| Outcome | Handled | Why |
|---|---|---|
| The price moved | yes | an answer; nothing was reserved, so refusing undoes nothing |
| No such selection | yes | an answer |
| Wallet will not hold the stake | yes | an answer, and nothing was reserved |
| Wallet timed out | **no** | no answer: the reservation may or may not exist |
| Wallet returned 5xx | **no** | same — the request may have been applied before the failure |

A refusal is complete to handle, because asking again would be told the same thing and there is
nothing behind it to undo. An open question is the opposite: it needs finding out what happened
before anything can be decided, which is a design rather than a branch.

**Insufficient funds was nearly missed.** It was first read as "a wallet failure, therefore day
8", when by the rule above it is plainly an answer and belongs here. Measured before fixing: wallet
replying `409` left the bet `PENDING`, the saga `IN_PROGRESS` at `RESERVING_FUNDS`, and the caller
holding an HTTP 500 — the most ordinary outcome in the whole flow producing an orphan and a
server error. Leaving a refused bet open is creating an abandoned row on purpose, which is exactly
the orphan problem `known-gaps.md` tracks for wallet.

All three share one `catch`, because all three get the same treatment, and the sequence itself
stays readable top to bottom. An earlier version wrapped each step in a helper taking a lambda; it
parameterised nothing — every refusal was handled identically — and it hid the three steps behind
a name that read like an instruction to refuse rather than a description of what happens if one
arrives.

### The refusal is translated at the client, not read at the saga

`WalletClient` turns the `409` into a domain refusal, so the orchestrator never sees a status
code. That is what will let day 9 move this call onto a queue without the saga's branches
changing: the refusal will arrive as a message instead of a response, and the handling stays put.

Two things that stayed deliberate while doing it. The `409` is read as *insufficient funds* by
inference — it is the only business refusal reserving can produce today — and that inference stops
being safe the moment wallet gains a second reason to answer `409`, at which point the response
needs a code and not only a status. And what wallet said goes to the log rather than into the
response: passing another service's raw body outwards would put its wording, its ids and its
internal shape into this service's public contract, where a caller could start depending on them.

---

## Decision 5 — Two new HTTP surfaces, deliberately thin

Wallet had no web layer at all, and sportsbook only a debugging trigger. Both gained the minimum
this flow needs and nothing more — no release endpoint, because nothing compensates yet.

**Wallet's controller goes through `WalletOperations`, not `WalletService`**, so the retry on an
optimistic lock collision applies to remote callers too. Wiring it to the service directly would
work until two bets arrived at once and then start refusing legitimate requests.

**Status codes are chosen for a caller that is a saga.** Telling apart "the answer is no" (409)
from "nothing was decided, ask again" (503) is what decides whether it compensates or retries. A
flat mapping rather than an exception hierarchy: that split has consequences beyond one service,
and inventing half of it here would prejudge it.

**Clients carry timeouts.** A read timeout is the setting most easily forgotten and most
expensive to omit: without one, a slow dependency holds a request thread per caller until the
pool is empty, and a service that was merely slow has taken down one that was healthy.

---

## Verification performed

**End to end, three services running**, with the odds simulator switched off so the price would
hold still between reading it and betting on it.

- A bet of 20.00 at the price sportsbook reported came back `CONFIRMED` with
  `appliedOdds = 3.270`; the saga read `COMPLETED` at step `CONFIRMING`; wallet held a
  `CONFIRMED` reservation for 20.00; the balance went 500.00 → 480.00 and the ledger read
  `TOPUP +500, RESERVATION −20, CONFIRMATION 0`.
- **Three further bets on the same wallet** left it at 420.00 with the ledger summing to 420.00 —
  the check that catches a deduction lost or applied twice, which a single happy path cannot.
- **A bet at a price that had moved** was refused with `409` and an error naming the bet, the
  balance stayed at 420.00, and the bet and its saga were both left `FAILED` with `currentStep`
  still `VALIDATING_ODDS` — pointing at the step that was in flight, as intended.

- **A bet larger than its wallet** was refused with `409` and the message
  `bet <id> was refused: insufficient funds`, the bet and saga were both left `FAILED` at
  `RESERVING_FUNDS`, and wallet was untouched: balance still 5.00, no reservation written. What
  wallet actually said appears in betting's log and not in the response.

**Automated**, with the two remote services stubbed: the happy sequence and the state it leaves,
the refusal on a moved price with wallet never called at all, the refusal on an unknown pairing,
the refusal when wallet will not hold the stake, and that funds are reserved under the bet's own
id rather than a fresh one — the last of which matters because that id is the unique key stopping
one bet from holding funds twice. Removing the refusal from the branch that closes a saga fails
the wallet-refusal test.

Assertions about wallet's balance and reservations live in another service's schema, so they
belong to the manual run rather than to betting's test suite. That boundary is a consequence of
the contexts being genuinely separate, not a gap in the testing.
