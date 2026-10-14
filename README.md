# fintech-ledger-core

A double-entry ledger backend: accounts, deposits, withdrawals and transfers with
idempotent APIs, pessimistic locking, a transactional outbox relayed to Kafka, and daily
reconciliation. Concurrency, idempotency, outbox delivery and token rotation are exercised
by integration tests against real PostgreSQL and Kafka (Testcontainers) in CI.

The original project plan (vision, domain model, roadmap) is in [docs/plan.md](docs/plan.md).

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Security | Spring Security 6 + JWT (JJWT), rotating refresh tokens |
| Persistence | Spring Data JPA (Hibernate 6) + PostgreSQL 16 |
| Migrations | Flyway |
| Messaging | Apache Kafka (transactional outbox relay) |
| API Docs | SpringDoc OpenAPI 3 |
| Tests | JUnit 5, Mockito, Testcontainers (PostgreSQL, Kafka) |
| Build / CI | Gradle 8, GitHub Actions, Docker |

## Quick Start

```bash
cp .env.example .env                 # set JWT_SECRET at minimum (openssl rand -hex 64)

# Option A — infrastructure in Docker, app from the IDE / Gradle
docker compose up -d postgres kafka
./gradlew bootRun --args='--spring.profiles.active=local'

# Option B — everything in Docker (outbox publishes to Kafka)
docker compose --profile full up --build

open http://localhost:8080/swagger-ui.html
```

`./gradlew build` runs unit and integration tests; Docker must be running for Testcontainers.

## Guarantees and how they are enforced

### 1. Double-entry: money is never created or destroyed

Every movement posts exactly one DEBIT and one CREDIT leg of the same amount.
External money is booked against a per-currency **settlement account** (seeded by `V10`):

| Operation  | DEBIT leg          | CREDIT leg         |
|------------|--------------------|--------------------|
| Deposit    | settlement account | customer account   |
| Withdrawal | customer account   | settlement account |
| Transfer   | sender             | recipient          |

For every account, balance = Σ CREDIT − Σ DEBIT, so Σ CREDIT = Σ DEBIT per currency at all
times. A settlement balance is ≤ 0: it mirrors money held on behalf of customers.
Ledger rows are append-only, enforced by a database trigger (`V14`), not by convention.

### 2. No overdraft, no deadlock under concurrency

`TransactionProcessor` row-locks both accounts with a single `PESSIMISTIC_WRITE` select
ordered by id. PostgreSQL sorts before it locks, so all movements acquire their locks in the
same global order. Balances, the transaction row, both ledger legs, the outbox event and the
success audit commit in one database transaction. A `CHECK` constraint backs the
no-negative-balance rule for customer accounts.

Ownership, status and currency are checked twice: once with a plain read before any lock
(so requests that can never succeed don't queue on the settlement row), and again under the
locks, where the result is authoritative.

### 3. Idempotency (per user, per `Idempotency-Key`)

| Situation | Result |
|-----------|--------|
| First request | executed, `201` |
| Same key, same payload | stored result replayed — a stored failure replays the same error |
| Same key, different payload | `409 IDMP_002` |
| Concurrent duplicates | exactly one executes; the others replay its outcome |
| Validation error (e.g. `10.5 KRW`) | nothing stored, key stays usable |

The anchor is the unique constraint `(requested_by, idempotency_key)`. A concurrent
duplicate waits for the account locks, then hits the constraint when its transaction row is
flushed (before any ledger leg is written), rolls back, and is answered by replaying the winner. Business rejections (insufficient funds,
frozen/closed account, currency mismatch) are recorded as `FAILED` rows by
`TransactionFailureRecorder` *after* the business transaction has rolled back, and the
failure audit references that stored row.

### 4. Amounts respect the currency

`@Digits(fraction = 2)` at the API boundary plus a per-currency check against
`java.util.Currency` (KRW has 0 decimal places, USD/EUR 2). Nothing is silently rounded.

### 5. Transactional outbox → Kafka

Events are written in the same transaction as the money movement. `OutboxProcessor` then:

1. **claims** due rows with `FOR UPDATE SKIP LOCKED` (safe across instances) and stamps `claimed_at`,
2. **publishes** to Kafka outside any DB transaction and waits for the broker ack,
3. **completes** with a conditional `UPDATE` (only if the claim is still current), or reschedules
   with exponential backoff and parks the event as `FAILED` after `outbox.max-retries`.

On the first publish failure the rest of the batch is returned to the queue untouched, so a
broker outage costs one send timeout per cycle. Claims older than `outbox.stale-claim-ms`
(crashed relays) are released for re-delivery, or parked once retries are exhausted; startup
fails if a batch could outlive its claim (`batch-size × send-timeout-ms ≥ stale-claim-ms`).
Scheduled jobs run on a 4-thread scheduler so the relay never waits on reconciliation.
Delivery is at-least-once; records are keyed by transaction id and carry an `eventId` header
for consumer-side de-duplication. Set `OUTBOX_PUBLISHER=log` for local runs without Kafka.

### 6. Reconciliation

`ReconciliationService` (daily, 02:00 UTC) checks two invariants:

- per account: `cached_balance` = ledger sum — each check runs in its own `REPEATABLE READ`
  read-only transaction, so both reads see one snapshot and concurrent transfers cannot
  produce false alarms;
- per currency: Σ CREDIT = Σ DEBIT.

Discrepancies are logged and written to the audit trail.

### 7. Authentication

Access tokens are short-lived JWTs. Refresh tokens are 256-bit random values stored as
SHA-256 digests (indexed O(1) lookup), rotated on every use with a conditional update.
Presenting a token that was already rotated revokes all of the user's sessions (reuse
detection, RFC 9700 §4.14). A token that was merely ended by login or logout is rejected
with `401` and nothing else, so an old device cannot sign the user out of a newer session.

## Project Structure

```
src/main/java/com/bugeon/fintechledger/
├── auth/            signup, login, JWT filter, refresh-token rotation
├── account/         accounts, settlement-account resolver, lifecycle (freeze/close)
├── transaction/     MoneyCommand, TransactionService (idempotency orchestration),
│                    TransactionProcessor (atomic execution), failure recorder
├── ledger/          double-entry posting, ledger queries
├── outbox/          outbox writer, relay (claim/publish/complete), Kafka & log publishers
├── reconciliation/  balance and ledger-invariant checks, daily scheduler
├── audit/           audit trail
├── common/          errors, API envelope, JSON helper, constraint detection
└── infrastructure/  security, scheduling, OpenAPI, configuration properties
```

## Database Migrations

| Version | Purpose |
|---------|---------|
| V1–V9 | users, refresh tokens, accounts, transactions, ledger, outbox, audit |
| V10 | account types + settlement accounts; normalises legacy currency case; backfills counter-legs of historical deposits/withdrawals |
| V11 | `failure_code`; idempotency key unique per requester |
| V12 | outbox `claimed_at` for stale-claim detection (in-flight legacy claims included) |
| V13 | revoke legacy BCrypt-hashed refresh tokens (users log in again once); `rotated_at` |
| V14 | append-only trigger on `ledger_entries` |

## Tests

| Suite | What it proves |
|-------|----------------|
| `ConcurrencyIT` | 16 parallel withdrawals never overdraw; opposing transfers don't deadlock; 10 parallel requests with one key move money once |
| `MoneyMovementIT` | balanced legs, failure recording and replay, key scoping, authorization, history visibility, append-only ledger |
| `OutboxIT`, `KafkaOutboxIT` | disjoint claims across pollers, stale-claim release, real delivery to Kafka |
| `RefreshTokenIT` | single-use rotation, reuse detection, multi-device login, concurrent refresh succeeds once |
| `ReconciliationIT` | drift detection and audit |
| `ApiFlowIT` | full HTTP flow through the security filter chain, error status mapping |
| unit tests | orchestration branches, outbox backoff, token handling, validation |

## Known trade-offs

- Settlement accounts are hot rows: deposits and withdrawals in one currency serialize on
  them. Production systems shard the settlement account or apply its balance asynchronously.
- Deposits and withdrawals simulate an external payment rail; in production they would be
  driven by the rail's confirmations (e.g. webhooks), not by end-user requests.
- Login revokes the user's other refresh tokens (single active session per user).
- A rejected transfer tells the sender whether the recipient account is frozen, closed or in
  another currency — the usual banking behaviour, but it is information about someone else's account.
- The REPEATABLE READ snapshot used by reconciliation is verified by a unit test of the
  transaction definition, not by a timing-dependent race test.
