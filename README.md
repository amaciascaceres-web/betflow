# BetFlow

An event-driven sports betting backend, built as a guided 20-day course. Each commit maps to one
day of the course.

## Running it

```bash
docker compose up -d && docker compose ps          # Postgres, Kafka, RabbitMQ, Redis
./gradlew build                                     # compile and test the six services
./gradlew :services:wallet-service:bootRun          # run one service
```

| Service | Port | Schema |
|---|---|---|
| identity-service | 8081 | identity |
| wallet-service | 8082 | wallet |
| sportsbook-service | 8083 | sportsbook |
| betting-service | 8084 | betting |
| settlement-service | 8085 | settlement |
| notification-service | 8086 | notification |

Health of any of them: `curl localhost:808X/actuator/health`.
RabbitMQ management UI: <http://localhost:15672> (guest/guest).

## Documentation

- `docs/design-response.md` — the answer to the business brief: services, data, communication
  and consistency, with no technology named.
- `docs/event-flows.md` — every message, what it triggers, and the database state through each
  failure scenario.
- `docs/adr/` — architecture decision records, one per significant decision.
- `docs/diagrams/context-map.md` — the six contexts and how they interact.
- `docs/diagrams/use-cases.md` — the business flows, in detail as they get built.
- `CLAUDE.md` — condensed project context and the 20-day index.
