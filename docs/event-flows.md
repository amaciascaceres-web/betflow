# Event flows and failure scenarios

The behaviour the implementation has to produce: which messages exist, what each one triggers,
and what the database looks like at every step — including when things go wrong.

This is a **specification of intended behaviour**, not a record of what is built. No technology is
named: redelivery, acknowledgement and at-least-once behave the same way whichever broker ends up
carrying them, and each transport gets chosen in its own decision record.

---

## 1. The message catalogue

**Facts** — something happened. The publisher does not know who listens and does not wait.

| Fact | Published by | Consumed by | What it triggers | Dedup key |
|---|---|---|---|---|
| `OddsChanged` | Sportsbook | Betting | Update the cached price used to validate bets | `eventId` |
| `BetPlaced` | Betting | Analytics, Audit | Record it. No effect on the business flow | `eventId` per consumer |
| `MarketResolved` | Sportsbook | Settlement | Settle every bet on that market | `eventId` **and** `betId` — see §2 |
| `BetSettled` | Settlement | Notification, Analytics, Audit | Notify the user, record it | `eventId` per consumer |

**Commands** — an order aimed at one recipient, which cares whether it ran.

| Command | Sent by | Handled by | Effect | Dedup key |
|---|---|---|---|---|
| `ReserveFundsCommand` | Betting | Wallet | Hold the stake against the bet | `commandId` |
| `ReleaseFundsCommand` | Betting | Wallet | Undo a hold — the compensation | `commandId` |
| `ApplyPayoutCommand` | Settlement | Wallet | Credit the winnings | `commandId` |

**The one query** — Betting asking Sportsbook whether a price is still current. It blocks, it
returns an answer, it changes nothing. Nothing to deduplicate.

---

## 2. The two dedup levels, and why both exist

| Level | Key | Question it answers | Protects against |
|---|---|---|---|
| Message | `eventId` / `commandId` | "Did I already finish this message?" | Redelivery of a message whose work was completed |
| Effect | `UNIQUE(bet_id)` on `Payout` | "Was this specific bet already paid?" | A message replayed after a **partial** failure |

A message id only works as a dedup key when one message causes **one** effect. `MarketResolved`
causes N, so its id can say "I started" or "I finished" but never "I finished 246 of 300". That
gap is exactly what `UNIQUE(bet_id)` fills.

**A message is marked finished only when it is completely finished.** Partial work is never
recorded as done — it is left to be redelivered and replayed in full, which is safe precisely
because every individual effect is protected on its own key.

---

## 3. Two modelling points to settle before writing the code

Both are ambiguous in the course document and both bite on day 6.

**What does `balance` mean?** Taking it as the **available** balance keeps the ledger honest —
the sum of all ledger entries equals `balance` at all times, which is the day 6 verification.
That makes the entry types:

| Type | Effect on balance |
|---|---|
| `TOPUP` | `+ amount` |
| `RESERVATION` | `− amount` |
| `RELEASE` | `+ amount` (undoing a reservation) |
| `CONFIRMATION` | **neutral** — the money already left on reservation; this records that the hold became a real spend |

**The entry type list is missing one.** Settlement credits winnings, and there is no entry type
for money arriving from a payout. `TOPUP` means "the user added funds" and reusing it would
corrupt the audit trail. A `PAYOUT` type is needed.

All the states below assume this model.

---

## 4. Placing a bet

Starting point for every scenario: `Wallet(balance=100, version=3)`, empty ledger, no bets.
The bet is **20 at odds 2.50**.

### Scenario A — happy path

| # | Action | Database after it commits |
|---|---|---|
| 1 | Bet request received | `Bet(B1, status=PENDING)`, `BetSagaState(currentStep=VALIDATING_ODDS, status=IN_PROGRESS)` |
| 2 | Ask Sportsbook: still 2.50? → yes | unchanged |
| 3 | Move the saga on, send `ReserveFundsCommand(C1, 20)` | `BetSagaState(currentStep=RESERVING_FUNDS)` |
| 4 | Wallet handles it — **one transaction** | `processed_commands(C1)`, `Wallet(balance=80, version=4)`, `FundsReservation(R1, PENDING, 20)`, `LedgerEntry(RESERVATION, −20, betRef=B1)` |
| 5 | Reply: reserved | unchanged |
| 6 | Confirm the bet, seal the hold | `Bet(B1, status=CONFIRMED, appliedOdds=2.50)`, `BetSagaState(status=COMPLETED)`, `FundsReservation(R1, CONFIRMED)`, `LedgerEntry(CONFIRMATION, 0, betRef=B1)` |

Ledger sums to 80, which is `Wallet.balance`. ✅ `BetPlaced` is published as a fact.

### Scenario B — the price moved

Step 2 answers *no*. `Bet(B1, status=FAILED)`, `BetSagaState(status=FAILED, failureReason="odds
moved 2.50 → 2.30")`. **Wallet is untouched — balance still 100, ledger still empty.** Nothing was
reserved, so there is nothing to compensate. This is why the price check comes first.

### Scenario C — not enough funds

Balance is 15, the bet is 20. Wallet's transaction: the insufficient-funds check runs **before**
any write, so the transaction rolls back entirely. `processed_commands` does **not** get `C1` —
nothing was done, so nothing is recorded as done. Betting marks the bet `FAILED`. This is a
*business* answer and it is final: no retry.

### Scenario D — failure after the reservation

Steps 1–5 as in A, so the database is at `balance=80` with `R1 PENDING`. Step 6 then fails.

| # | Action | Database after |
|---|---|---|
| 6 | Confirmation fails | `BetSagaState(status=FAILED, failureReason=…)` |
| 7 | Send `ReleaseFundsCommand(C2, R1)` | — |
| 8 | Wallet handles it | `processed_commands(C2)`, `Wallet(balance=100, version=5)`, `FundsReservation(R1, RELEASED)`, `LedgerEntry(RELEASE, +20, betRef=B1)` |
| 9 | | `Bet(B1, status=FAILED)` |

Balance is back to 100 and the ledger has **three** entries: reservation, release, and nothing
confirmed. The history shows what happened — a compensation is a new event in the record, not an
erasure. `version` went 3 → 4 → 5: two real writes, not a rollback.

### Scenario E — the reservation command arrives twice

The broker redelivers `C1` because the acknowledgement was lost. Wallet's transaction starts with
`INSERT processed_commands(C1)`, which violates the primary key. Log at INFO, acknowledge, **apply
nothing**. Balance stays 80, one reservation, one ledger entry, `version` unchanged at 4.

Without the dedup row this would reserve 40 against a 20 bet.

### Scenario F — two bets at once on the same wallet

Balance 100, two bets of 60 arriving simultaneously. Both read `version=4`. The first writes and
bumps to 5. The second tries to write against `version=4`, which no longer exists → optimistic
lock failure → **retry**, this time reading `balance=40`, which fails the funds check. One bet
confirmed, one rejected for insufficient funds, balance 40. Never 100 − 60 − 60.

---

## 5. Settling a market

`MarketResolved(E1)` for market M1 with **300 bets**: 120 winners, 180 losers. Processed in
chunks of 100.

### Scenario G — happy path

| # | Action | State after |
|---|---|---|
| 1 | Consume `E1`, open the batch | `SettlementBatch(marketId=M1, totalBets=300, status=IN_PROGRESS)` |
| 2 | Chunk 1 commits | 100 `Payout` rows |
| 3 | Chunk 2 commits | 200 `Payout` rows |
| 4 | Chunk 3 commits | 300 `Payout` rows |
| 5 | Batch complete | `SettlementBatch(status=COMPLETED, processedAt=…)`, `processed_events(E1)`, **message acknowledged** |
| 6 | Pay the winners | 120 `ApplyPayoutCommand`s → Wallet credits each, `LedgerEntry(PAYOUT, +…)` |
| 7 | | 120 × `BetSettled` published → notifications |

Losers get a `Payout` row of amount 0 with status `PAID`. They are settled, just not paid
anything — the row is what proves the bet was dealt with.

### Scenario H — two chunks fine, the third fails *(the case you asked about)*

The connection drops partway through chunk 3, at bet 247.

**State at the moment of the crash:**

| | One transaction per chunk | One transaction per bet |
|---|---|---|
| `Payout` rows | **200** — chunk 3 rolled back whole | **246** — each committed on its own |
| `processed_events` | no `E1` | no `E1` |
| Message | **not acknowledged** | **not acknowledged** |
| `SettlementBatch` | `IN_PROGRESS` | `IN_PROGRESS` |

Either is correct. The difference is only how much work gets redone. Per-bet commits waste less;
per-chunk commits are cheaper to run. Neither relies on rollback for safety.

**The broker redelivers `E1`.** Settlement reprocesses **all 300** from the start:

- The first 200 (or 246) hit `UNIQUE(bet_id)` → logged at INFO, **skipped, not errors**.
- The remaining 100 (or 54) are created.
- All 300 accounted for → `SettlementBatch(status=COMPLETED)`, `processed_events(E1)`, acknowledge.

Final state is identical to scenario G. **Exactly 300 payout rows, never 301.**

This is the whole point: the replay is blind and dumb — it does not need to know where it died.

### Scenario I — the market is already fully settled and the message arrives again

`processed_events` already has `E1`. Skip immediately, acknowledge, touch nothing. Without this
level you would rescan 300 bets to discover all 300 bounce off the constraint — correct, but
wasteful. This level is an optimisation; the level below it is the guarantee.

### Scenario J — a bet that can never be settled

Bet 247 is corrupt: it points at a wallet that does not exist.

| Attempt | Result |
|---|---|
| 1 | 299 settled, 247 fails → not acknowledged → redelivered |
| 2 | 299 skipped via the constraint, 247 fails again |
| 3 | same |
| → | retry limit reached → **message parked in the dead-letter queue + alarm** |

Final state: 299 `Payout` rows, `SettlementBatch(status=PARTIAL, settled=299, failed=1)`, `E1`
**not** in `processed_events`, message sitting in the dead-letter queue.

The critical part: **an incomplete batch is never silently acknowledged.** "Never fail to pay a
winner" is absolute, so the last resort is a visible, investigable parked message — not a
swallowed error. Parking it also stops one bad row blocking every other market behind it.

### Scenario K — Wallet is down when the payouts are sent

Settlement's own work is already durable: 300 `Payout` rows exist, batch `COMPLETED`. The payout
commands queue up. Nothing is lost, nothing is retried by hand; when Wallet comes back it drains
the queue, deduplicating on `commandId`. This is the difference a command makes over a blocking
call — Settlement was never waiting.

---

## 6. What `SettlementBatch` needs to carry

These scenarios only work if the batch can distinguish *complete* from *stopped halfway*. The
minimal shape cannot. It needs:

```
SettlementBatch(id, marketId, totalBets, settledCount, failedCount,
                status [IN_PROGRESS | COMPLETED | PARTIAL], startedAt, processedAt)
```

Without `status` and the counts, answering "is this market fully settled?" means counting
`Payout` rows and comparing — which is precisely the work the batch record exists to save.

---

## 7. The rules all of this comes down to

1. **A message is marked finished only when it is completely finished.** Never at the start.
2. **The dedup key matches the unit of the effect** — per message when one message means one
   effect, per bet when one message means many.
3. **A duplicate is not an error.** Log it at INFO and continue; it is the mechanism working.
4. **The dedup record and the business effect share one transaction.** Split them and a crash in
   between either loses the work permanently or duplicates it.
5. **Never wrap a whole batch in one transaction.** Commit per bet or per chunk, so progress
   survives and replay is cheap.
6. **A compensation is a new entry in the history, not an erasure**, and it must be safe to run
   twice.
7. **Business failure and technical failure are different.** Insufficient funds is final. A
   timeout deserves another attempt.
8. **When retries run out, park it loudly.** Silence is the one outcome the business forbids.
