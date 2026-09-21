# ADR-005 — Protecting a balance: optimistic locking, a derived ledger, and guarded transitions

- **Status:** accepted
- **Date:** day 6
- **Context:** Wallet is the context ADR-001 separated for its transactional consistency, and this
  is where that has to be paid for. The failure to prevent is ordinary and expensive: two requests
  read the same balance, each checks `balance − amount ≥ 0` against what it read, both pass, both
  deduct, and the wallet has spent money it never had.

---

## Decision 1 — Optimistic locking on the aggregate

**Chosen:** `@Version` on `Wallet`, with a bounded retry outside the transaction.

There are three ways to stop the overdraft, not two. The third is worth recording because it is
the one this project has already used twice, and because dismissing it without argument would be
dishonest:

| | Pessimistic `SELECT … FOR UPDATE` | Optimistic `@Version` | Conditional `UPDATE … WHERE balance >= ?` |
|---|---|---|---|
| The loser | waits | fails and retries | is told "no" immediately |
| Lock held | through all application code | none | from the statement to the commit |
| Work discarded | none | the whole attempt | none |
| Protects | whatever is locked | **the whole row** | **only the named columns** |
| Round trips | 2+ | 2+, times retries | **1** |

**The conditional update is not a naive option, and was measured rather than assumed.** Against
this project's Postgres at `read committed`: the second transaction blocks on the row lock, and
when the first commits it does not apply its update to the version it originally matched —
Postgres **re-evaluates the `WHERE` against the newly committed row**, finds `40 >= 60` false, and
reports `UPDATE 0`. The balance ended at 40.00 with one deduction. The arithmetic is done by the
engine from the current row, so there is no window between reading and writing, because there is
no read.

**Chosen against it anyway**, for reasons that are about tomorrow rather than today:

- **`@Version` guards the whole row.** The conditional guards `balance`. This aggregate is the one
  most likely to grow — a daily limit, a block, a currency — and the version covers what appears
  without anyone remembering to cover it.
- **The retry machinery is needed regardless** once this operation is orchestrated by a saga.
- **The failure mode is worth owning.** An exception raised at commit, which only a fresh
  transaction can recover from, is a thing to have built once deliberately.

**Costs accepted.** A retry loop that must be placed correctly, and work discarded on every
collision. Under *sustained* contention on one wallet that trade inverts: retries pile up, latency
becomes unpredictable, and the conditional update — which serialises rather than repeats — would
be the better answer. Nothing here has that profile; a wallet belongs to one user.

**One caveat on the comparison.** The conditional update's freedom from retries is a property of
`read committed`, not of the technique. At `repeatable read` the same experiment returns
`ERROR: could not serialize access due to concurrent update`, and the application must retry after
all. Spring uses the database default, which is `read committed`, so this does not bite here — but
the advantage is conditional and should not be quoted as absolute.

---

## Decision 2 — The retry lives outside the transaction

**Chosen:** a separate component wrapping `WalletService`, three attempts, fixed 80 ms pause.

Three facts force the shape, and each rules out the obvious shortcut:

1. **The failure surfaces at commit**, not at the statement that caused it — Hibernate checks the
   version when the transaction flushes. A `catch` inside the service method would never fire,
   because the method has already returned.
2. **A retry must open a new transaction.** The failed one is marked rollback-only and can do
   nothing further. Only a fresh one re-reads the balance, which is the entire point: the second
   attempt has to see what the winner left behind.
3. **Only the collision is retried.** `InsufficientFundsException` is the correct answer to the
   question asked, and passes straight through. Retrying it would ask again and be told the same
   thing, turning one refusal into three.

Those combine into the behaviour the domain wants, which day 1's event-flows document had already
written down: two bets of 60 against 100 collide, one wins, the loser retries, reads 40 and is
refused for want of funds. Never 100 − 60 − 60, and never an error the user did not cause.

Caught as Spring's `ObjectOptimisticLockingFailureException`, which is what exception translation
produces. Catching JPA's `OptimisticLockException` compiles and never matches.

The backoff is fixed rather than exponential: contention on one wallet is short-lived by nature,
since the writer that won is already committing.

---

## Decision 3 — The balance is derived, not asserted

**Chosen:** an append-only `ledger_entry`, with signed amounts, that the balance must agree with.

A financial system does not store a number and trust it. It stores what happened, and the number
follows — which is what makes it auditable and reconstructible at any past point.

Amounts carry their sign so the check is a plain `SUM`: `TOPUP` and `RELEASE` add, `RESERVATION`
subtracts, and `CONFIRMATION` is **zero**, because the money left the available balance when it
was reserved. Confirming records that a hold became a real spend; it moves nothing. That
definition of `balance` — the *available* balance — was settled on day 1 and is what keeps the sum
honest.

**Immutability is enforced by the methods that exist.** `LedgerEntryRepository` extends
`Repository`, not `JpaRepository`, and declares only `save` and a read. There is no `delete`, no
`deleteAll`, no `saveAll` — not unused, unavailable. The same reasoning as ADR-004: a rule the
compiler holds is worth more than one a comment requests.

---

## Decision 4 — State transitions are guarded, and therefore idempotent

**Chosen:** a reservation's status is never read, decided and written back. It moves through a
conditional update that fires only from the state it expects.

```sql
UPDATE funds_reservation SET status = :target
 WHERE id = :id AND status = :expected
```

This costs nothing and buys two things. It removes the window in which two callers both see
`PENDING`. And it makes confirming or releasing twice harmless: the second call matches no row and
writes nothing, so the caller can tell "done now" from "already done" without reading first.

That second property is not decoration. These operations are going to arrive as messages one day,
and a message can be delivered twice — at which point the number of deliveries must not reach the
balance.

### Repeats are ignored; contradictions are refused

The two are not the same event and do not deserve the same answer. A repeat is the same decision
arriving twice, which is ordinary once these are messages. A contradiction is the *opposite*
decision arriving, which means two callers reached different conclusions about one bet.

| Arrives | Current state | Wallet |
|---|---|---|
| `confirmFunds` | already `CONFIRMED` | ignores — same decision again |
| `confirmFunds` | `RELEASED` | **refuses** |
| `releaseFunds` | already `RELEASED` | ignores |
| `releaseFunds` | `CONFIRMED` | **refuses** |

Refusing is the load-bearing half. Returning quietly on a contradiction would leave betting
believing a bet failed and its funds were returned, while wallet had already spent them — with no
exception, no log, and a ledger that reads as a perfectly ordinary confirmation. The money is gone
in either case; the difference is whether anybody finds out.

**And wallet records it, rather than leaving that to whoever catches the exception.** The service
that arbitrated is the one that should say what it decided; otherwise the only trace of a
wallet-side contradiction lives in the caller's log, and the service where it happened is silent.
The warning carries the reservation, the bet, the transition attempted and the state found — the
bet in particular, because the reservation id is wallet's handle while the bet is what betting and
the saga think in, and without it the two halves of an investigation share no key.

Worth being clear about the limits: a log is evidence that something contradictory was *attempted*.
What the system *did* is in the ledger, which is append-only, has no `delete` on its repository,
and does not rotate.

**The ambiguous zero is handled rather than ignored.** Writing nothing means either "already
where you wanted it" or "somewhere else entirely", and only a second question separates them:
landing on the target is silent, anything else is an `IllegalReservationStateException`. Same cost
as in ADR-004, and it is becoming this project's recognisable tax on the technique.

---

## Decision 5 — The read/write split from ADR-004, applied where it is not obvious

**Chosen:** reads return `FundsReservationDto` and `LedgerEntryDto`; the one method that hands
back an entity is `WalletRepository.findById`, and its caller is the only one that mutates what
it gets.

The rule is ADR-004's, but wallet is where it stops being a preference and starts removing a
hazard. A reservation's status is changed by a guarded statement that writes underneath the
persistence context, so an entity read before that statement keeps reporting the state the
transition has just moved away from — and asking again returns the same stale instance rather
than the row. The service used to defend against that with ordering ("always load after the
transition, never before") and a comment. A projection removes the question: there is no cached
instance to prefer.

`Wallet` is the deliberate exception, and it is the whole reason the rule is stated by side
rather than as a ban. Reserving has to read a balance, decide, and write, and the version
travelling with the entity is what makes that safe. A value object could not carry it.

| | Read as | Because |
|---|---|---|
| `Wallet` | the entity | it is mutated, and `@Version` rides on it |
| `FundsReservation` | a value | only read; its status moves underneath the ORM |
| `LedgerEntry` | a value | only read, and an accidental write to an append-only table would be silent |

---

## Decision 6 — A bet reserves funds once, stated in the schema

**Chosen:** `UNIQUE (bet_id)` on `funds_reservation`.

It is a domain invariant first: one bet, one hold. But it also does quiet work that no locking
strategy does. If the same request arrives twice **concurrently**, both transactions may pass the
balance check — neither optimistic locking nor a conditional update makes reserving idempotent,
because subtracting is accumulative. The second insert then violates the constraint and **takes
its own deduction down with it**, because both are in one transaction.

That is deduplication at the level of the effect, keyed on the effect's own natural identity
rather than on a message id. It is independent of the locking decision and would be needed with
any of the three.

---

## Verification performed

- **The overdraft cannot happen.** Two threads released together against a balance of 100, each
  reserving 60: exactly one reservation, one `InsufficientFundsException`, balance 40.00, ledger
  summing to 40.00.
- **And the retry does not merely discard the loser.** The same collision against a balance of
  200 leaves *both* bets taken and the balance at 80.00. A retry that gave up would be safe and
  wrong.
- **Collisions genuinely occur**: two per run, consistently, across five runs. The tests
  nevertheless assert outcomes rather than interleavings — demanding a collision would make them
  fail on a quiet machine for no reason — and the retry policy itself is pinned deterministically
  with a mocked service, which is the one place in this module where mocking is the right tool: a
  branch has no state, no database and no timing.
- **The test has teeth.** Removing `@Version` fails both concurrency scenarios.
- **The ledger agrees with the balance** after a sequence of reserve, reserve, confirm, release.
- **All three ways a guarded transition writes nothing are exercised**, because the branch that
  sorts them out is doing real work: confirming twice is silent and adds no second entry;
  confirming a released reservation and releasing a confirmed one are both refused, with the
  balance left alone; and a reservation that does not exist is refused before the question is
  even asked. Swallowing the zero instead — the tempting simplification — fails all three.

  Both outcomes are recorded where they happen: the repeat at `INFO`, the refusal at `WARN` with
  the reservation, the bet and the states involved.

  The third case is not hypothetical from day 9 on: a saga that gives up and compensates, followed
  by a confirmation that was merely slow, is an ordinary redelivery. Returning quietly there would
  leave one service believing a bet is confirmed while the other has already handed the money
  back, with nothing logged and nothing thrown.
- **A second reservation for the same bet is rejected** by the unique constraint.
- **A confirmation and a release racing leave one winner and a loud loser.** Run with both
  released together against the same reservation: exactly one `IllegalReservationStateException`,
  exactly three ledger entries — the opening balance, the hold, and one of the two ways a hold can
  end — and the ledger still equal to the balance. Four entries would mean both branches ran.
  Stable across eight runs.
- **The state guard carries six tests across three layers.** Removing `AND r.status = :expected`
  fails the two repository tests, the three service-level transition tests and the race above. One
  clause holds up idempotence, mutual exclusion and the protection of the balance at once.
- The conditional-update and isolation-level comparisons in decision 1 were run against this
  project's Postgres rather than reasoned about, as was the statement Hibernate actually emits:
  `update wallet.wallet set balance=?, user_id=?, version=? where id=? and version=?`. The whole
  of decision 1 rests on a clause no line of this code contains.
