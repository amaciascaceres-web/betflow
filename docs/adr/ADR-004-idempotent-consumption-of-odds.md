# ADR-004 — Consuming odds idempotently: no deduplication table, and why

- **Status:** accepted
- **Date:** day 4
- **Context:** Betting now writes when it consumes. Kafka delivers at least once, so the same
  `OddsChanged` will be handed to this consumer twice sooner or later — a crash between doing the
  work and committing the offset guarantees it. The question is what to do about that, and the
  answer turns out to depend on the shape of the effect rather than on the existence of
  duplicates.

---

## Decision 1 — No deduplication table in betting-service

**Chosen:** strategy (1), the naturally idempotent operation. `OddsChanged` sets a selection's
price to a value. Applying it a second time lands on the state the first application produced, so
there is nothing to protect against and no table to protect it with.

The alternative — a `processed_events(event_id PK, processed_at)` row written in the same
transaction as the effect — is the textbook answer and would work. It is rejected here because it
is not free, and the cost falls in the wrong place:

- **It adds a write to every event** to avoid redundant work on the rare duplicate. Normal event:
  one write without the table, two with it. Duplicate: one redundant write without the table,
  one colliding insert with it. As an optimisation it is a net loss, and as a safeguard it
  duplicates one the effect already provides.
- **It grows one row per event, forever.** That needs date partitioning or a purge job, which is
  another decision to take, defend and operate — for a table that is protecting something already
  protected.

### The rule this leaves behind

Deduplication is decided per consumer, from the shape of that consumer's own effect. It earns its
place when:

- the effect is **accumulative** — `reserveFunds` subtracts an amount, and no rewriting turns
  that into a replacement; or
- the effect is **expensive or irreversible** even though idempotent — paying, emailing, calling a
  third party. There what the table saves is not a wrong state, it is the work.

A single-row overwrite is neither. The same event can therefore need a table in one consumer and
none in another, which is what "idempotence is per consumer, not global" actually means in
practice.

**Not a claim that duplicates do not happen.** They do, and this consumer receives them. The claim
is that receiving one costs nothing and changes nothing.

---

## Decision 2 — Last-writer-wins by event timestamp, expressed in JPQL

**Chosen:** a conditional bulk update, and a check-then-insert only for a selection never seen
before. No native SQL.

```java
UPDATE SelectionOdds s SET s.odds = :odds, s.oddsUpdatedAt = :ts
 WHERE s.selectionId = :id AND s.oddsUpdatedAt < :ts
```

The condition belongs in the statement, not in Java between a read and a write. The database
evaluates it while holding the row, so two writers cannot both read the old value and both
conclude they are newer. Two properties follow:

- **Duplicates cost nothing.** A redelivered event carries the same timestamp, the comparison is
  false, and no row is written. The result would be correct without the condition — it would
  rewrite identical values — but it would pay for it.
- **Order stops mattering.** *Idempotent* and *order-insensitive* are different properties and
  only the first comes free. Kafka orders records within a partition, so an older event should
  never follow a newer one — except that ADR-002 decision 1 records repartitioning as breaking
  exactly that guarantee, since keys move and one market's records end up on two partitions with
  no ordering between them. On that day the projection stays correct instead of silently
  reverting to a stale price.

### Why not `INSERT ... ON CONFLICT`

Postgres would settle the whole thing in one atomic statement, with no ambiguity and no second
question. It was implemented that way first and then deliberately replaced. Portability is the
usual argument and the weakest one here — the stack fixes Postgres. Two others carry the decision:

1. **Cache coherence with the ORM.** A native statement goes underneath Hibernate, which cannot
   know which entities it touched. A second-level cache would go on serving the old price
   indefinitely, with nothing to indicate it. A bulk JPQL update names the entity, so Hibernate
   invalidates the region. There is no second-level cache today; the point is that adding
   `@Cache` to the entity would turn the native version into a silent bug, and nothing would
   fail to warn about it.
2. **Confinement is cheaper than judgement.** "SQL lives in one place, and JPQL is the default"
   is a rule a reviewer can apply at a glance. "Native SQL when justified" has to be argued every
   time, and the arguments drift.

### What it costs, stated rather than hidden

- **Zero rows is ambiguous.** The row may be absent, or the event may not be newer. Only a second
  question separates them, so the first appearance of a selection costs two extra round trips —
  once per selection in the system's lifetime. In steady state it remains a single statement.
- **The insert is not atomic with the check before it.** Nothing can enter that gap here, because
  a partition is read by one consumer of the group and a selection belongs to one market, which
  is the partition key: one thread owns every event for a given selection. That is worth being
  precise about — the safety comes from an invariant of the architecture, not from the database,
  and **nothing enforces it**. An admin endpoint correcting a price by hand, a backfill job or a
  migration script would reopen the gap without a line of this code changing. `ON CONFLICT` would
  have given the same guarantee unconditionally.
- **The bulk update bypasses the persistence context.** It is safe because this transaction never
  reads an entity before writing it; a future read in the same transaction would need
  `clearAutomatically`.

### Two assumptions this leans on

`<` rather than `<=`, so two distinct events sharing a timestamp would see the second ignored. At
microsecond resolution that is unlikely, and `<=` would trade it for a write on every duplicate.

The timestamp is the producer's clock. With several sportsbook instances, skew could make an older
event look newer. The robust answer is a monotonic per-selection version assigned by the producer.
Notably it cannot be the Kafka offset, which would work today and fail precisely in the
repartitioning scenario the comparison exists for.

### The upsert was compensating for a missing fact

Worth recording, because it reframes the problem. The insert branch exists at all because betting
learns that a selection exists from a *price change*. The stream carries no fact saying "this
selection exists".

Had sportsbook published a catalogue fact — a market opened, with its selections — betting would
create the row on that event and `OddsChanged` would only ever be an update. The ambiguity would
not need resolving, the check-then-insert would not exist, and one plain conditional statement
would be the entire implementation. The gap is in the event contract, not in JPQL.

Not built: inventing a fact nobody asked for is a larger change than this day carries, and the
current shape works. Recorded so that the next person does not read the insert branch as
necessary complexity.

## Decision 3 — Betting keeps a local projection of the price

**Chosen:** `selection_odds`, one row per selection, fed by the event stream.

This has to be reconciled with ADR-002, which says sportsbook owns the price and that nothing
touching money trusts a consumer's copy. Both stand, because they answer different questions:

| | Who answers |
|---|---|
| "What is this selection roughly priced at?" — listing a market, a screen, a feed | this projection |
| "Can this bet be accepted at this price?" — money moves | sportsbook, synchronously |

The projection is allowed to be seconds out of date and allowed to disagree with sportsbook. It is
not allowed to be the basis of a decision that moves money. The entity is deliberately not called
`Selection`: it is not the same concept, only a cached fact about one.

---

## Decision 4 — Projections to read, entities to write, and the compiler holds the line

**Chosen:** every read returns a `SelectionOddsDto` built by the query itself, and the repository
declares no method that hands back an entity.

The immediate reason is the conditional update: it goes straight to the database, so any entity
already in the persistence context keeps its old values. What makes that dangerous rather than
merely awkward is that re-querying does not fix it. Measured here: after the update,
`SELECT s FROM SelectionOdds s WHERE ...` returned the **stale** price and the same object
instance — Hibernate ran the SQL, read the new value, and discarded it in favour of the instance
it already held. `SELECT s.odds FROM ...` returned the new one. Entity identity wins over what was
just read from disk; a constructor expression has no identity to preserve.

So the rule is narrower than "use DTOs": the projection has to be **built by the query**. Loading
an entity and mapping it to a DTO afterwards maps a stale object and buys nothing.

Two further reasons, and the first is worth more than the one above:

- **An accidental write becomes impossible.** A managed entity adjusted in passing — rounding a
  price for display — is flushed at commit as an `UPDATE` nobody requested, with no `save()` call
  to find by searching. There is nothing to adjust on a record.
- **Nothing can be lazily loaded outside its transaction.** Not yet relevant with no associations
  mapped, and unavoidable once `Bet` and `BetSagaState` arrive.

**The cost, and where it is not paid.** A blanket ban on entities outside the repository drains
behaviour out of the model and into services. That cost is real — sportsbook's `Selection` carries
`changeOddsTo`, which encodes why the price is replaced rather than adjusted, and a strict rule
would move it into a service and leave getters behind. Here it is zero: `SelectionOdds` is a row
in a read model and has no behaviour to lose. The split is therefore by side, not by principle —
projections on the read side, entities on the write side, where an invariant would live.

**Enforced rather than agreed.** The repository extends `Repository` rather than `JpaRepository`,
so the only methods that exist are `existsById`, `save`, the conditional update and two
projections. `findById` is not unused — it is unavailable.

That surface is the whole guarantee, which is why the entity keeps its accessors. The instance
anyone can hold is one they constructed a line earlier, so there is no stale state to read off it,
and the accessors pay for themselves the moment a test needs to look at a row it wrote. Removing
them would add nothing the repository does not already prevent.

---

## Decision 5 — Tests bring their own Postgres

**Chosen:** Testcontainers, a real Postgres 17 started by the test suite.

Day 3's consumer could be tested with the datasource switched off, because its effect was a log
line. That stops being honest the moment the effect is a row: what is under test is `ON CONFLICT`
resolving a collision and a timestamp comparison picking a winner, and an in-memory database that
merely resembles Postgres cannot answer for either.

The container is started once per JVM and shared, rather than one per test class.

### Each property is proved at the layer that owns it

The three properties were first asserted end to end, through the broker. They passed, but the
arrangement was wrong: none of them has anything to do with Kafka. A broker's only contribution is
handing over the same event twice, and calling the service twice reproduces that exactly, without
waiting on anything.

| Test | Proves | Needs |
|---|---|---|
| `SelectionOddsRepositoryTest` | what the conditional update does: absent row, newer, equal timestamp, older, decimal scale, reading one market | Postgres |
| `OddsProjectionServiceTest` | the three properties — duplicate, replay convergence, out-of-order — and the branch that resolves "zero rows" | Postgres |
| `OddsChangedEndToEndTest` | the one thing neither can reach: the path from the wire to the column | Postgres + broker + full context |
| `OddsChangedConsumerTest` | ordering and offset movement, which are Kafka's properties | broker |

The payoff is not only speed. Dropping the timestamp comparison now fails two repository tests and
two service tests, and leaves the end-to-end test passing — so a broken query points at the query.
Under the previous arrangement the same mistake failed an end-to-end test, which says a record went
in and something came out wrong, and leaves the reader to find out where.

### Consequence: `contextLoads` stops using whatever is listening on localhost

Adding Flyway made the existing context test migrate a real developer database — it started
against `localhost:5432`, created the schema there, and would have passed or failed depending on
which containers happened to be up. A test asserting that the context starts on its own has to own
what it starts against, so it now brings its own Postgres and broker.

---

## Verification performed

- **The same event delivered twice leaves the projection unchanged**, end to end through a real
  broker and a real database.
- **A full replay converges.** Applying the whole stream twice — the test-sized equivalent of
  rewinding the group's offsets to the beginning — lands on an identical projection.
  At-least-once may redeliver any part of the log at any time, and a consumer that survives a
  complete replay survives every smaller case of it.
- **What that test is not evidence for**, since it is easy to read it as more than it is:
  convergence is stability, not correctness. A projection that settles on wrong values settles
  just as firmly. Removing the selection filter from the conditional update was confirmed to
  leave the convergence test passing while three others failed.
- **An older event does not overwrite a newer one**, confirming decision 2.
- **The tests were confirmed to fail when they should**, against both implementations. Dropping
  the timestamp comparison fails the out-of-order test alone and leaves convergence intact — the
  correct signal, since the comparison protects against disorder and not against repetition.
  Making the effect accumulate instead of replace fails all three. An "innocent" counter column
  would do the same, which is the clearest way to see that idempotence is a property of the
  effect and not a layer added on top of it.
- **Switching from `ON CONFLICT` to JPQL changed no test.** The suite asserts behaviour, not
  implementation, so replacing the persistence strategy left all three passing untouched — and
  the mutation checks above were repeated against the new one.

### The same thing, by hand, against the running system

The automated convergence test republishes records. The operational version rewinds the group, and
it behaves differently enough to be worth doing once:

- **A live group cannot be rewound.** `--reset-offsets` refuses while a member is connected:
  *"Assignments can only be reset if the group is inactive, but the current state is Stable."* A
  replay in production therefore requires a stop window, which is a real constraint and not a
  detail of the tooling.
- With the log frozen at 861 records and the projection already holding the state those records
  produce, the group was rewound to offset 0 and betting restarted. It reprocessed all 861 and
  **applied none of them** — 861 ignored, zero writes — leaving 23 rows byte-identical to the
  snapshot taken before. A full replay against a converged projection does nothing at all, which
  is the property this decision claims.
- The snapshot it matched had been produced by the `ON CONFLICT` implementation, so the same run
  also shows the two strategies agreeing on the final state rather than merely each being
  self-consistent.
- On an earlier run, starting from a projection that did not yet hold every selection, 353 of 861
  records were ignored as not newer. That is decision 2's comparison visibly at work on real data
  rather than in a constructed test.

**One false start worth recording.** The first attempt compared the pre-replay state against the
post-replay state and would have shown a difference. The fault was in the setup, not the system:
the projection table had been dropped while the group's committed offsets were left untouched, so
the "before" state was not the result of consuming the log. Comparing one full replay against
another removes that dependency, and is the comparison the property is actually about.

### A day-3 test that was never sound

`theOffsetAdvancesOnlyOnceTheListenerAcknowledges` had to be fixed twice, and both faults were the
same mistake in different clothes: reading a figure that other traffic also moves.

1. It first asserted an **absolute** committed offset, which only held while two random market keys
   happened to hash onto different partitions. It failed as soon as they collided.
2. Rewritten to assert a **delta**, it still took its baseline after publishing the first record —
   so if the listener had already acknowledged that record, the measured advance came out one
   short. This is the flavour of flake that passes repeatedly and then fails in CI.

It now takes the baseline before publishing anything, sums every partition rather than guessing
which one the key lands on, and waits for the committed offsets to stop moving before reading
them. Confirmed over six consecutive clean runs.
