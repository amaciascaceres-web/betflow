# ADR-002 — Publishing odds changes: partitions, ownership of the current price, and the dual write

- **Status:** accepted
- **Date:** day 2
- **Context:** Sportsbook has to announce that a price moved. Processing an old price after a
  newer one means accepting a bet at odds that no longer exist, so ordering is a business
  requirement, not a technical preference. This is also the first event stream in the system, so
  the choices made here set the pattern for the ones that follow.

---

## Decision 1 — Six partitions on `odds-changed`

**Chosen:** six partitions, one replica locally.

A partition count is a ceiling on parallelism: within a consumer group, one partition is read by
exactly one consumer, so a seventh consumer of a six-partition topic sits idle. The number is
therefore chosen for the parallelism a busy live event could need, **not** for today's volume.

**Why not raise it later instead.** Repartitioning changes `hash(key) % partitions`, so existing
keys move to different partitions. Ordering is only guaranteed *within* a partition, so the
guarantee is broken across the moment of the change: records for one market would exist on two
partitions with no ordering between them. It is a migration, not a config tweak.

**Why not many more.** Every partition costs broker file handles, memory and replication
bookkeeping, with no benefit until there are consumers to use it.

**One replica is local-only.** It also means `acks=all` currently proves nothing about
durability: with a single replica there is exactly one acknowledgement to wait for. In
production this would be at least three replicas, and only then does `acks=all` carry its
meaning.

### Consequence: automatic topic creation is disabled

The broker runs with `auto.create.topics.enable=false`, and the topic is declared as a
`NewTopic` bean in the service. If creation were automatic, this decision would be made silently
by a broker default the first time anything published — the decision would still exist, it would
just never have been taken by anyone. Declaring it in code also puts the contract in version
control instead of in the shell history of whoever set up the environment.

---

## Decision 2 — `marketId` as the partition key

**Chosen:** the partition key is `marketId`, not `selectionId` and not absent.

Ordering has to hold across every selection of the same market, because they move together: a
market's prices are repriced as a set, and applying part of an update out of order would leave an
internally inconsistent book. Keying by `selectionId` would order each selection independently
and lose that. Keying by nothing spreads records round-robin with no ordering at all.

**Cost accepted:** a very busy market is a hot key. All of its traffic lands on one partition and
cannot be spread, so one market can become the bottleneck. That is the price of the ordering
guarantee, and it is the right trade here — a slow book is recoverable, accepting bets at stale
prices is not.

**Known simplification, stated on purpose.** A real market does not move one selection in
isolation: prices within a market share a probability budget (the book's overround), so a real
feed reprices every selection together whenever one of them changes. This project's simulator
does not model that — it moves one selection at a time, independently, with no effect on its
siblings. That is a deliberate scope cut, not an oversight: the goal here is to exercise the
event mechanics this decision is actually about (ordering, partitioning, the dual write), not to
build a correct odds-pricing engine. If the domain reasoning above is ever checked against the
running system, this is the gap that would surface.

---

## Decision 3 — Sportsbook persists the current price

**Chosen:** `Selection` carries `current_odds`. The event stream announces changes; the column
holds the answer.

The day 1 model gave `Selection` only `id, marketId, name`, on the reading that the event was
"published, not persisted". Building day 2 showed that cannot stand:

- **The synchronous check has no answer without it.** Placing a bet validates the price against
  this service. An authority that stores no state would have to replay the whole log on every
  request — that is event sourcing, a far larger commitment that nothing here has argued for.
- **ADR-001 already claims it.** It records that "the current odds have a single source of
  truth". Without the column that statement is false.
- **A separated read model presupposes a write model.** A derived view is not the source of
  truth; if the price lived nowhere but the log, the log would be the source of truth, which is
  again a different architecture.
- **A simulated feed needs prior state.** A price is a value that moves. Without reading the
  previous one you produce noise, not a market.

**Not superseding ADR-001**, which stands as the record of what was decided on day 1. Finding the
gap on day 2 is the point: it is recorded here rather than edited into yesterday.

### Consequence: Flyway arrives on day 2

Persisting anything needs tables, and `ddl-auto: none` provides none. Letting Hibernate generate
them and introducing Flyway later would leave Flyway facing a schema it did not author, to be
patched around with a baseline. Flyway is introduced now, for this service only, and `ddl-auto`
moves to `validate` so that drift between an entity and its migration fails at startup.

---

## Decision 4 — Two writes, one of which can be lost

**Chosen:** commit the database first, publish after the commit succeeds. The exposure is
documented rather than solved.

A price change writes to two systems — the table and the log — and no transaction spans both.
Both orderings fail, differently:

| Order | Failure in between leaves |
|---|---|
| Publish, then commit | Consumers hold a price the authority does not have. |
| Commit, then publish | A change nobody is told about. |

The second is chosen, and the publish is hooked to run after commit so a rolled-back change is
never announced. The remaining exposure is a **lost announcement**, tolerable here for two
reasons specific to this data:

1. Prices change continuously. The next change to the same selection repairs a consumer's view
   within seconds — this is self-healing data, unlike a bet or a payment.
2. Nothing involving money trusts a consumer's copy. The check that protects a bet asks this
   service directly.

**The general solution is a transactional outbox** — write the event to a table in the same
transaction, and relay it separately — which converts the dual write into a single local
transaction plus an at-least-once relay. It is not introduced because the exposure above is
genuinely tolerable for prices, and because the cost of the outbox is best paid where the data
does not repair itself. Recorded so the omission is visible rather than accidental.

---

## Decision 5 — Idempotence as a package, and stating defaults on purpose

`acks=all` and `enable.idempotence=true` are configured explicitly. Idempotence is already the
client default, so this changes no behaviour — it records intent, because a reader cannot tell a
default from a decision, and defaults change between versions.

They are not independent settings: idempotence requires `acks=all`, `retries > 0` and
`max.in.flight.requests.per.connection <= 5`, and the producer refuses to start otherwise.

**What it does and does not buy.** Producer idempotence removes duplicates caused by the
producer's *own* retries, which is the case where an acknowledgement is lost but the record did
arrive. It does nothing about a consumer that processes a record and dies before committing its
offset. That duplicate is unavoidable at this layer and is the consumer's problem to solve.

---

## Decision 6 — The serializer uses the application's ObjectMapper

Declaring `value-serializer: JsonSerializer` in configuration is not sufficient. The client
instantiates serializers by class name through a no-argument constructor, so the serializer
builds an `ObjectMapper` unrelated to the one the application configured. Observed symptom: an
`Instant` published as `1789378312.540544000` instead of an ISO-8601 string.

That is this service's published contract being changed by accident, and every consumer would
then be written around the accident. A producer factory is declared explicitly so the serializer
is constructed with the application's mapper.

---

## Verification performed

- `kafka-topics.sh --describe` reports `PartitionCount: 6`.
- 135 events published across 5 markets, 0 failures.
- Every `marketId` resolved to exactly one partition, confirmed both from the producer's
  `RecordMetadata` and independently from the broker with `print.partition=true`.
- Only 3 of the 6 partitions received traffic. With five keys hashed into six buckets,
  collisions are expected — **partitioning guarantees affinity, not balance**. Even distribution
  is a property of having many keys, and assuming otherwise is how a hot-partition problem gets
  missed.
- A published event round-trips as `"timestamp": "2026-09-14T09:33:39.368784Z"`.
- The stored price and the published price agree after each change.
