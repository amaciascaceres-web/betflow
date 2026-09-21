# BetFlow — project context

A **guided learning project** (not a real product): simulate the event-driven backend of a
sports betting company to build real depth in distributed systems, microservices, Kafka/RabbitMQ,
Sagas, CQRS and observability. The end goal is being able to **defend every architectural
decision in an interview**.

The scope's source of truth is `plan-betflow_v6.html` (a 20-day / 4-week course, ~2h a day).
**v6 supersedes v5**: after day 4's decision, the deduplication table moved out of
betting-service (where `OddsChanged` is naturally idempotent and needs none) and into day 9,
where `reserveFunds` is accumulative and genuinely needs it. Days 4, 5, 9 and 12 differ from
v5; everything else is unchanged.
It is deliberately untracked — see `.gitignore`. There is no need to re-read it in full: the day
index below summarises what each day covers. When the detail of a specific day is needed, extract
only that section from the HTML.

## Working rules

- **One commit per day/session of the course.** Subject: `day N: <day title>`.
- **Never move to the next day until the current day's verification passes** (the course's
  golden rule).
- Every day carrying an architectural decision produces or extends an **ADR** under `docs/adr/`.
- **Everything in English** — code, comments, ADRs, diagrams, commit messages. Domain
  identifiers are taken **verbatim from the plan** (`reserveFunds`, `appliedOdds`,
  `StaleOddsException`, `LedgerEntryType.TOPUP`); do not invent synonyms, so the code and the
  course document stay searchable against each other.
- **Commits carry no AI attribution** — no `Co-Authored-By`, no session trailer. This is the
  user's own study repository.
- Never reach into another bounded context's database: only its public API or the events it
  publishes.
- **No hindsight in the docs.** An artefact describes what has been decided and why, not what a
  later day will do. Do not name a technology before the ADR that chooses it exists, and do not
  annotate diagrams with the day something lands — classify interactions as fact / command /
  query and let the transport be decided when it is argued.
- Before calling a day done, run the steps under that day's heading in the plan (the plan's prose
  is in Spanish — the heading to look for is literally `Verificación`).

## Stack

| Piece | Version / choice |
|---|---|
| Java | 17 (the only JDK installed) |
| Gradle | 9.3 (wrapper), multi-module, version catalog in `gradle/libs.versions.toml` |
| Spring Boot | 3.5.16 (BOM imported as a `platform`, no `io.spring.dependency-management` plugin) |
| Postgres | 17 — **one container, one schema per service** |
| Kafka | `apache/kafka:4.1.2`, KRaft mode (no Zookeeper) |
| RabbitMQ | `rabbitmq:4.1-management` (UI on :15672, guest/guest) |
| Redis | `redis:8.2` (CQRS read model, day 14) |
| Zipkin | `openzipkin/zipkin:3.6` (day 16) |
| Migrations | Flyway — sportsbook day 2, betting day 4, wallet day 6; never hand-written DDL |

## Layout

```
services/<name>-service/     one Gradle module per bounded context
docker/postgres/init/        init scripts (CREATE SCHEMA per service)
docs/design-response.md      answer to the business brief (no technology named)
docs/event-flows.md          messages, triggers and DB state per failure scenario
services/*/src/main/resources/db/migration/  Flyway migrations (sportsbook from day 2)
docs/adr/                    ADR-00X-*.md
docs/diagrams/               context-map.md, use-cases.md (Mermaid)
plan-betflow_v6.html         the course document (untracked)
```

Base package: `com.alejandromacias.betflow.<service>`.
Main class: `<Service>ServiceApplication`.

## Services and ports

| Service | Port | Schema | Entities it owns |
|---|---|---|---|
| identity-service | 8081 | identity | `User(id, email, passwordHash, createdAt)`, `Role(id, name)` |
| wallet-service | 8082 | wallet | `Wallet(id, userId, balance, version)`, `LedgerEntry(…, type, amount, timestamp, betReferenceId)`, `FundsReservation(…, amount, status)`, `processed_commands` |
| sportsbook-service | 8083 | sportsbook | `SportEvent(id, name, startDate, status)`, `Market(…, type, status)`, `Selection(id, marketId, name, currentOdds, oddsUpdatedAt)` — publishes `OddsChanged` |
| betting-service | 8084 | betting | `Bet(…, amount, appliedOdds, status)`, `BetSagaState(…, currentStep, status)`, `selection_odds` (local read projection, not the source of truth for a price) |
| settlement-service | 8085 | settlement | `SettlementBatch(…, processedAt, totalBets)`, `Payout(id, betId UNIQUE, amount, status)` |
| notification-service | 8086 | notification | `NotificationLog(…, type, channel, status, sentAt)` |
| analytics-service | 8087 | analytics | no fixed entity — day 13 |
| audit-service | 8088 | audit | `audit_log(eventId, eventType, payload, receivedAt)`, append-only — day 13 |

Enums: `LedgerEntryType` RESERVATION/CONFIRMATION/RELEASE/TOPUP · `FundsReservation.status`
PENDING/CONFIRMED/RELEASED · `Bet.status` PENDING/CONFIRMED/FAILED · `BetSagaState.currentStep`
VALIDATING_ODDS/RESERVING_FUNDS/CONFIRMING and `.status` IN_PROGRESS/COMPLETED/FAILED ·
`Market.status` OPEN/CLOSED/RESOLVED · `SportEvent.status` SCHEDULED/IN_PLAY/FINISHED ·
`Payout.status` PENDING/PAID.

Postgres: database `betflow`, user/password `betflow`/`betflow`, port 5432.

## Messaging contracts

- **Kafka = facts** (many unknown consumers, replay): `odds-changed` (6 partitions, key =
  `marketId`, `acks=all`, producer idempotence), `market-resolved`, `bet-placed`, `bet-settled`.
- **RabbitMQ = commands** (an order aimed at one recipient, with a DLQ): direct exchange
  `wallet-commands`, routing key `wallet.reserve-funds`, queue `wallet.reserve-funds.queue`,
  DLX `wallet-commands.dlx` → `wallet.reserve-funds.dlq`.
- **Idempotence**: decided per consumer, from the shape of its own effect — not applied by
  default. Betting's `OddsChanged` effect is an overwrite ("set odds to X"), so it needs no
  dedup table; see ADR-004. Where a dedup table *is* needed it lives in the **same transaction
  as the business effect** and keys on the message's own id: `processed_commands`
  (`command_id`) in wallet, day 9. Settlement deduplicates at the effect level instead,
  through `UNIQUE(bet_id)` on `Payout`, because one message produces N payouts.

## Commands

```bash
docker compose up -d && docker compose ps          # infrastructure
./gradlew :services:wallet-service:bootRun          # run one service
SERVER_PORT=8087 ./gradlew :services:betting-service:bootRun   # 2nd instance (day 3)
./gradlew build                                     # compile + test everything
curl -s localhost:8082/actuator/health              # one service's health
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic odds-changed
```

## Day index

**Week 1 — the event backbone**
1. Bounded contexts: 6 modules + docker-compose + ADR-001. *(Decision: split Wallet from Betting)*
2. Kafka producer: topic `odds-changed` with 6 partitions, key `marketId`, `acks=all`, odds simulator. *(Decision: partition count)*
3. Consumers and consumer groups: `@KafkaListener` with `ack-mode: MANUAL`, 2 instances, rebalancing.
4. Idempotent consumption: Flyway + `selection_odds` projection, overwrite effect, last-writer-wins by event timestamp. No dedup table here. *(Decision: naturally idempotent vs dedup table)*
5. Review (no code).

**Week 2 — the place-a-bet Saga**
6. Wallet: append-only ledger + `@Version`, retry outside the transaction, guarded status transitions. *(Decision: optimistic vs pessimistic vs conditional update)*
7. Orchestrated Saga, happy path: `BettingSagaOrchestrator`, `BetSagaState`. *(Decision: orchestration vs choreography)*
8. Saga: compensation + business vs technical exception hierarchy.
9. RabbitMQ: `ReserveFundsCommand` replaces the REST call, DLQ and retries; first real dedup table (`processed_commands`). *(Decision: Kafka vs RabbitMQ)*
10. Review (no code).

**Week 3 — settlement, choreography, CQRS**
11. Settlement: `MarketResolved` consumer, `PayoutCalculator` (pure domain), batches. *(Decision: synchronous vs throttled)*
12. Idempotent settlement: `UNIQUE(bet_id)` on `Payout`; one failed row must not abort the batch.
13. Choreography: analytics-service + audit-service without touching the producers. *(Decision: choreography)*
14. CQRS: Redis projection + `SseEmitter` on `/odds-stream` + a live HTML page. *(Decision: when to split a read model)*
15. Review (no code).

**Week 4 — observability, resilience, closing**
16. Distributed tracing with Micrometer/Brave + Zipkin, propagating `traceId` across Kafka **and** RabbitMQ.
17. Metrics: Prometheus + Grafana, consumer lag and queue depth, with an alert and a justified threshold.
18. Resilience: Resilience4j circuit breaker on the synchronous call to sportsbook. *(Decision: when to use one)*
19. Load testing with k6 (stages + thresholds) and chaos: kill a broker mid-peak.
20. Interview rehearsal: defend the 10 decisions from memory.
