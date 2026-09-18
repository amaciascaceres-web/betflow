# ADR-003 — Consuming odds changes: acknowledgement, group, and who owns the schema

- **Status:** accepted
- **Date:** day 3
- **Context:** Sportsbook publishes `OddsChanged` and nobody reads it. Betting attaches to that
  stream, and does so as a service that runs in more than one instance — so the questions are not
  only "how do I read a topic" but "what happens when two copies of me read the same topic", and
  "when exactly does a record count as consumed".

---

## Decision 1 — `ack-mode: MANUAL` rather than the default

**Chosen:** `spring.kafka.listener.ack-mode: MANUAL`, with the listener calling
`acknowledge()` after the work succeeds.

The offset is the group's bookmark: the only record of what has been consumed. Committing it is a
separate act from doing the work, and everything interesting lives in the gap between the two.
There are three regimes, not two, and the middle one is the one that gets overlooked:

| Regime | Who commits, and when | Failure it allows |
|---|---|---|
| `enable.auto.commit=true` | The Kafka client, on a timer, inside `poll()` — regardless of whether the work succeeded | A poll returns 100 records, 10 are processed, the timer commits all 100, the process dies. The other 90 are never processed and never redelivered: **silent loss**. |
| `ack-mode: BATCH` (the default) | The container, after the listener returns normally for the whole polled batch | No loss. A crash after the effect and before the commit redelivers: **duplicates**. |
| `ack-mode: MANUAL` | This service, by calling `acknowledge()` | The same duplicates. Plus a new one of its own: a path that forgets to acknowledge stops the offset silently. |

Two defaults worth stating precisely, because both are commonly misremembered and both were
checked against the version in use rather than recalled:

- Spring's default is **not** Kafka's auto-commit. `ContainerProperties` initialises `ackMode` to
  `BATCH`, and the container sets `enable.auto.commit=false` when nothing else set it. Out of the
  box, Spring already commits after the listener returns.
- A manual acknowledgement mode and auto-commit cannot be combined: the container asserts against
  it at startup rather than letting the two quietly fight.

**So this is not a correctness fix.** `BATCH` is already safe against loss, and is a perfectly
defensible choice for a listener whose work finishes when the method returns. `MANUAL` is chosen
because the acknowledgement point is about to become part of an argument — the effect and the
record of the effect have to move together — and because making it explicit now means the
question "when is this record finished?" has a visible answer in the code rather than an implied
one in a framework default.

**Cost accepted:** the responsibility is now real. Any path out of the listener that does not
acknowledge stalls that partition's offset for the whole group, and the symptom is not an error —
it is lag that grows and a replay on the next restart.

---

## Decision 2 — What manual acknowledgement does not buy

Worth writing down because the mistake is common: `MANUAL` does not make consumption
exactly-once, and no acknowledgement mode can.

The offset lives in Kafka and any effect this service has lives elsewhere. They are two systems
with no transaction spanning them — the same dual write recorded in ADR-002 decision 4, seen from
the other end. Whichever is committed second, a crash in between leaves the pair inconsistent:
the work done and the record of it lost, so the record is delivered again.

The guarantee available here is **at-least-once**, and the answer to duplicates is not a stricter
acknowledgement — it is a consumer for which a repeat is harmless. That is a separate decision and
is not made here.

---

## Decision 3 — Betting declares its own copy of the event

**Chosen:** `OddsChangedEvent` is declared in betting-service, next to its consumer, rather than
imported from sportsbook-service or extracted into a shared module.

A shared types artifact would look like the obvious economy and would quietly undo the split
ADR-001 argued for: two services on one release train, a field renamed for one consumer's
convenience recompiling every other consumer, and a producer that can no longer tell who depends
on what. The duplication is the smaller cost, and it buys each side the freedom to drop a field it
does not use.

What is shared is the JSON on the topic. Each side maps it onto a type it owns. The same
reasoning applies to the topic name, which betting declares as its own constant instead of
importing sportsbook's.

**What the separate copy protects against, and what it does not.** It decouples the two services
from each other's *class* names: renaming or moving `OddsChangedEvent` in sportsbook is now
invisible here. It does nothing about *field* names, which travel in the JSON itself. Measured on
this consumer: an added field is ignored silently, and a renamed one arrives as `null` — no
exception, no log, just a missing value that surfaces later and far from its cause. Additive
changes are safe; a rename is a breaking change to the contract regardless of how either side
declares its type. Nothing here enforces that yet, and the omission is recorded rather than
solved.

### Consequence: the producer stops publishing a Java class name

By default the JSON serializer stamps a `__TypeId__` header carrying the producer's
fully-qualified class name, and a consumer's deserializer reads it to decide what to build. That
puts a Java package structure inside a published contract: renaming a class in sportsbook would
break consumers, and every consumer would need sportsbook's classes on its classpath — precisely
what "each context owns its model" is supposed to prevent.

Type information is now off on the producer and ignored on the consumer, which states its own
target type instead. Either alone would work — verified by publishing with the header on and
watching this consumer ignore it — so this is a contract decision and not a fix for a failure.
Both are kept: the consumer defends itself against any producer, and the producer stops asking
every future consumer to remember the same defence.

It is a correction to day 2's code, made on day 3 because a contract with no reader has no
observable defect. The first consumer is what turned it into one.

---

## Decision 4 — One consumer group per bounded context, reading from the beginning

**Chosen:** group `betting-service-group`, `auto-offset-reset: earliest`, group name configured in
`application.yml` and not repeated on the annotation.

A group is the unit of work sharing: every instance of betting-service joins the same one, so the
six partitions are divided among them and each record is handled once by the service. A second
group — another context entirely — gets its own complete copy of the stream. That property is what
makes a fact publishable to consumers who do not know about each other, and it is why the group
name belongs to the service, not to the listener method.

`earliest` applies only when the group has no committed offset, which is to say once. It means a
new consumer reads the history rather than only what arrives after it started. For prices that is
arguably wasteful and certainly harmless; `latest` would have made the first start silently
different from every later one, which is worse to debug.

The group is configured in one place. The course's outline puts it on the annotation as well;
with both, the annotation wins and the configured value becomes decoration that looks
authoritative and is not.

---

## Decision 5 — Wiring the consumer explicitly, and how it fails when you do not

The deserializer is constructed with the application's `ObjectMapper`, the same decision as
ADR-002 decision 6, for the same reason: a deserializer named in configuration is instantiated by
the client through a no-argument constructor, with a mapper that is not the application's.

Declaring the `ConsumerFactory` alone is not enough, and the way it fails is the interesting part.
Spring Boot's auto-configured listener container factory asks for a
`ConsumerFactory<Object, Object>`, and that request is generic-aware: a factory typed to this
service's event does not satisfy it. Boot then falls back to building a consumer factory from
properties alone, where the default value deserializer is `StringDeserializer` — so the
application starts, the listener is registered, and the first record arrives as a `String` where
an event was declared. Nothing is wrong at startup; the failure is at runtime, on the first
record.

The listener container factory is therefore declared here too, and the acknowledgement mode is
copied onto it from `spring.kafka.listener.ack-mode` so that configuration remains where the
decision is read.

---

## Verification performed

Two instances of betting-service against the broker in `docker-compose.yml`, with
sportsbook-service publishing throughout.

**First start.** The group had no committed offset, so `earliest` applied: the consumer replayed
the 806 records left on the topic by day 2 — 809 log lines in the first four seconds, the extra
three being live traffic that arrived during the replay — and then followed the feed. Over the
whole session the group consumed 853 records (851 on instance A, 2 on instance B) with 0 errors,
against 47 published live by sportsbook-service. That the replay succeeds at all is the wire
format confirming itself: the ISO-8601 `timestamp` is read back into an `Instant`, and no
`__TypeId__` header is needed or looked for.

**Rebalance when a second instance joined.**

| Time | Instance A | Instance B |
|---|---|---|
| 14:44:35 | assigned `[0, 1, 2, 3, 4, 5]` | — |
| 14:45:38.762 | **revoked** `[0, 1, 2, 3, 4, 5]` | starting |
| 14:45:38.795 | — | assigned `[0, 1, 2]` |
| 14:45:39.088 | assigned `[3, 4, 5]` | — |

A gave up everything and took half back, rather than handing over only the three partitions that
moved. That is eager rebalancing, and the reason is visible in the client configuration:
`partition.assignment.strategy = [RangeAssignor, CooperativeStickyAssignor]`. The group picks the
first strategy every member supports, so `RangeAssignor` wins and the incremental behaviour of
the cooperative assignor is never used. The processing pause it costs was ~326 ms here; on a
topic with real backlog it is the whole reason the cooperative assignor exists. Left as the
default deliberately — it is worth knowing which one is actually in effect before changing it.

**The broker's own view**, mid-test: two members, three partitions each, lag 0 on every partition.
Partitions 0, 1 and 4 held no records at all, which matches ADR-002 — five market keys hashed into
six partitions give affinity, not balance.

**Rebalance when an instance died.** B was killed at 14:46:05; A revoked `[3, 4, 5]` at
14:46:05.789 and was assigned all six at 14:46:06.094 — ~305 ms, with nothing restarted by hand.
The handover was immediate rather than waiting out `session.timeout.ms` because B shut down
gracefully and left the group on the way out; a `kill -9` would have cost the full timeout.

**Acknowledgement actually moves the offset.** Final committed offsets were 185, 413 and 255 on
partitions 2, 3 and 5, with lag 0 — and with `ack-mode: MANUAL` nothing but the listener's
`acknowledge()` call could have advanced them. The automated test asserts exactly this, and was
confirmed to fail when the `acknowledge()` call is removed: the listener still receives every
record and the group still believes it has read nothing.

**No record was handled by both instances** across either rebalance — 0 event ids appear in both
logs. Worth stating carefully: that is an observation of a clean run, not a guarantee. The
guarantee on offer is at-least-once, and a crash placed between the effect and the
acknowledgement would have produced a duplicate here.

**A seventh consumer** in this group would be assigned nothing and sit idle. A partition is read
by exactly one member of a group, so six partitions is the ceiling on this group's parallelism —
which is what makes the partition count in ADR-002 a scaling decision rather than a formality.
