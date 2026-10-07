# Idempotent Payment Ledger API

A payment ledger REST API built with **Java 21, Spring Boot 3.5, PostgreSQL 16, Spring Data JPA and Flyway**. It records payments and refunds between accounts as double-entry postings. Its correctness guarantees are enforced by PostgreSQL itself (a unique constraint, row locks, CHECK constraints and a deferred trigger), not by in-memory checks. They are verified by Testcontainers integration tests, a k6 load test followed by a SQL invariant check, and a container restart/recovery script.

**Guarantees**

1. **A request is applied at most once.** Retries with the same `Idempotency-Key` never create a second transaction. The key is claimed with `INSERT … ON CONFLICT DO NOTHING` in the same database transaction as the ledger writes, so it survives API restarts.
2. **Concurrent duplicates collapse to one.** When identical requests race, exactly one creates the transaction and the rest replay it. Parallel payments never overdraw an account, and a payment can be refunded only once.
3. **The books always balance.** Every posted transaction writes an equal debit and credit, and PostgreSQL rejects any commit that breaks this.

**Quick start** (needs Docker only):

```bash
docker compose up -d --build --wait            # PostgreSQL + API on 127.0.0.1
curl -s localhost:8080/actuator/health          # {"status":"UP", ... "db":{"status":"UP"} ...}
docker compose down                             # stop (add -v to also delete this project's data)
```

Swagger UI is then at http://localhost:8080/swagger-ui.html. If port 5432 or 8080 is taken, set `POSTGRES_PORT` / `API_PORT`; see [Running locally](#running-locally).

**Documentation:** [RUNBOOK.md](RUNBOOK.md) covers operations, the load test, the recovery check and an interview demo. [docs/MEASUREMENT_REPORT.md](docs/MEASUREMENT_REPORT.md) has the reproducible test and latency evidence. [docs/PUBLISH_CHECKLIST.md](docs/PUBLISH_CHECKLIST.md) is the release checklist. [FINAL_PROJECT_REPORT.md](FINAL_PROJECT_REPORT.md) is the full engineering report.

> **Scope:** this is a portfolio project that has been validated locally and in containers. It is not deployed, not production-operated, and its load-test figures are not a capacity benchmark.

## Architecture

```
controller/   REST endpoints, header + Bean Validation, HTTP status mapping
service/      Use cases (payment, refund). One @Transactional method = one DB transaction
repository/   Spring Data JPA repositories + one JDBC fragment for INSERT ... ON CONFLICT
domain/       JPA entities (Account, LedgerTransaction, LedgerEntry), status/type enums, Money
dto/          Request/response records (amounts serialized as strings)
exception/    ApiException + GlobalExceptionHandler (RFC 9457 problem+json with a `code` field)
metrics/      LedgerMetrics: Micrometer counters for transaction outcomes and error codes
db/migration  Flyway schema: tables, CHECK/UNIQUE constraints, ledger triggers
```

Tables:

- **`accounts`**: currency, `balance NUMERIC(19,4)`, and `allow_negative_balance`. A funding or settlement account sets that flag; for every other account the `CHECK (allow_negative_balance OR balance >= 0)` constraint stops overdrafts.
- **`transactions`**: payments and refunds. `UNIQUE (idempotency_key)`, `amount > 0`, source ≠ destination, and a partial unique index that allows at most one non-failed refund per payment.
- **`ledger_entries`**: append-only DEBIT/CREDIT lines, one pair per posted transaction.

All money is `BigDecimal` in Java and `NUMERIC(19,4)` in PostgreSQL. The API accepts up to 4 decimal places and returns amounts as strings (`"12.3456"`), so JSON clients never convert them to floats.

## How idempotency works

`POST /transactions` and `POST /transactions/{id}/refund` both require an `Idempotency-Key` header. The key is stored on the transaction row, and a SHA-256 **fingerprint of the request** is stored next to it.

Each request runs in a single database transaction:

1. **Fast path.** If a row with this key already exists, compare fingerprints. If they match, return the stored transaction (`200 OK`, `Idempotent-Replayed: true`). If they differ, return `422 IDEMPOTENCY_KEY_REUSED`.
2. **Lock accounts.** Lock both accounts with `SELECT ... FOR UPDATE`, always in id order so that opposite-direction transfers can't deadlock.
3. **Claim the key atomically:**
   ```sql
   INSERT INTO transactions (...) VALUES (...) ON CONFLICT (idempotency_key) DO NOTHING
   ```
   If a concurrent request with the same key is in flight, PostgreSQL makes this insert **wait on the unique index** until the other transaction commits. It then inserts nothing (0 rows), and the request loads and returns the winner's row. Nothing is checked in memory; the unique constraint is the only arbiter.
4. **Post and commit.** Write the ledger entries, update the balances and set the status to `POSTED` (or `FAILED`), then commit.

Consequences:

- N concurrent identical requests return **one** transaction: one `201`, and `200` replays for the rest. The integration tests check this with 16 parallel HTTP calls.
- If the process crashes mid-request, everything rolls back, **including the key claim**, so a retry starts clean. No transaction is left stuck in `PENDING`.
- Requests rejected before step 3 (400 validation errors, 404 unknown account, 422 currency mismatch) don't consume the key.
- Business failures such as insufficient funds are **recorded** as `FAILED` transactions, so a retry replays the same outcome.
- A replay returns the transaction's *current* state. For example, after a refund, a replayed payment request shows `REFUNDED`.

Refunds follow the same pattern. They also lock the original payment row first, so concurrent refunds with *different* keys are serialized: exactly one succeeds and the others get `409 TRANSACTION_ALREADY_REFUNDED`.

## Double-entry ledger invariant

Each posted transaction writes exactly two entries: **DEBIT** the account the money leaves and **CREDIT** the account it goes to, for the same amount. An account's balance is `sum(credits) − sum(debits)`. The cached `accounts.balance` changes only in the same database transaction as its entries.

The invariant is enforced by the database, not only by the application:

| Rule | Mechanism |
|---|---|
| Debits = credits, single currency, per transaction | `DEFERRABLE INITIALLY DEFERRED` constraint trigger, checked at COMMIT |
| Entries can't be changed or removed | `BEFORE UPDATE OR DELETE` trigger. Corrections are new reversing entries (refunds) |
| Amounts are positive | `CHECK (amount > 0)` on transactions and entries |
| No overdraft on normal accounts | `CHECK (allow_negative_balance OR balance >= 0)` |
| One transaction per idempotency key | `UNIQUE (idempotency_key)` |
| At most one successful refund per payment | Partial unique index on `refund_of_id WHERE status <> 'FAILED'` |

### Statuses

| Status | Meaning |
|---|---|
| `PENDING` | Row inserted and key claimed. Exists only inside the creating DB transaction |
| `POSTED` | Entries written and balances updated |
| `FAILED` | Rejected by a business rule (`INSUFFICIENT_FUNDS`). No entries |
| `REFUNDED` | A posted payment that a `REFUND` transaction has fully reversed |

## API

Swagger UI: http://localhost:8080/swagger-ui.html · OpenAPI JSON: http://localhost:8080/v3/api-docs

| Method | Path | Description |
|---|---|---|
| `POST` | `/accounts` | Create an account |
| `GET` | `/accounts/{id}` | Account with its current balance |
| `POST` | `/transactions` | Create a payment (`Idempotency-Key` required) |
| `GET` | `/transactions/{id}` | Transaction with its ledger entries |
| `POST` | `/transactions/{id}/refund` | Fully refund a posted payment (`Idempotency-Key` required) |

### Examples

```bash
# A funding account (may go negative) and a customer wallet
curl -s localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Funding","currency":"USD","allowNegativeBalance":true}'
curl -s localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Alice","currency":"USD"}'

# Create a payment. Re-running this exact command returns the same transaction with 200 + Idempotent-Replayed: true
curl -i localhost:8080/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001' \
  -d '{"sourceAccountId":"<FUNDING_ID>","destinationAccountId":"<ALICE_ID>","amount":"100.00","currency":"USD","description":"Top-up"}'
```

```http
HTTP/1.1 201
Idempotent-Replayed: false
Location: /transactions/334a34c9-...

{"id":"334a34c9-...","type":"PAYMENT","status":"POSTED","amount":"100.0000","currency":"USD",
 "entries":[{"accountId":"<FUNDING_ID>","direction":"DEBIT","amount":"100.0000","currency":"USD"},
            {"accountId":"<ALICE_ID>","direction":"CREDIT","amount":"100.0000","currency":"USD"}], ...}
```

```bash
# Refund the payment
curl -i -X POST localhost:8080/transactions/<TX_ID>/refund -H 'Idempotency-Key: refund-order-1234'
```

Errors use `application/problem+json` with a stable `code`:

```json
{"title":"Unprocessable Entity","status":422,"detail":"Idempotency-Key was already used for a different request",
 "instance":"/transactions","code":"IDEMPOTENCY_KEY_REUSED"}
```

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED` (with `errors[]`), `MISSING_HEADER`, `SAME_ACCOUNT` |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND` |
| 409 | `TRANSACTION_ALREADY_REFUNDED`, `TRANSACTION_NOT_REFUNDABLE`, `CONSTRAINT_VIOLATION`, `CONCURRENT_MODIFICATION` |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_MISMATCH`, `TRANSACTION_NOT_REFUNDABLE` (refund of a refund) |

## Running locally

Requirements: Docker. A JDK 21 is needed only to run Maven on the host; the wrapper supplies Maven itself.

```bash
docker compose up -d --build --wait   # PostgreSQL 16 + the API, both published on 127.0.0.1 only
curl -s localhost:8080/actuator/health
```

To run the API from your IDE or with Maven instead, start only the database with `docker compose up -d postgres`, then run `./mvnw spring-boot:run`. Flyway migrates the schema on startup.

Ports are configurable: `POSTGRES_PORT` defaults to 5432 and `API_PORT` to 8080. If 5432 is taken, run `POSTGRES_PORT=55432 docker compose up -d postgres` and point a host-run API at it with `DB_URL=jdbc:postgresql://localhost:55432/ledger`.

## Running tests

```bash
./mvnw clean verify
```

Continuous integration: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the same command (JDK 21, Testcontainers on the runner's Docker) on every push and pull request.

The integration tests use **JUnit 5 + Testcontainers**. They start a throwaway `postgres:16-alpine` container (Docker must be running) and exercise the real HTTP API on a random port. They cover:

- **Idempotency:** a duplicate key returns the same transaction with no extra entries; the same key with a different body gets 422; `"10"` and `"10.0000"` count as the same request; a missing or blank key gets 400; `FAILED` results are replayed.
- **Concurrency:** 16 parallel payments with the same key produce exactly 1 transaction and 2 entries. 12 parallel refunds with the same key produce 1 refund. 10 parallel refunds with different keys produce 1 success and 9 conflicts. 20 parallel payments against a 100.00 balance produce exactly 10 posted, 10 failed, and a final balance of 0.
- **Ledger:** balanced entries; account balances equal the sum of their entries; the global sum of debits equals the sum of credits; refund reversal; refund rules.
- **Database constraints:** an unbalanced insert is rejected at commit; UPDATE and DELETE on entries are rejected; a duplicate key is rejected by `UNIQUE`; an overdraft is rejected by `CHECK`.
- **Metrics:** transaction counters by outcome, including a concurrent same-key burst (1 `posted`, the rest `replayed`); the Prometheus scrape contains HTTP and ledger metrics but no keys, ids or amounts; health shows the db component plus liveness and readiness probes.
- **Restart durability:** a payment is created by one application instance, that instance is shut down, and a new instance against the same database replays the request: same id, still 1 row and 2 entries.

## Operational validation

Three things are layered on top of the tests: metrics, a k6 load test, and a Docker restart/recovery check. **[RUNBOOK.md](RUNBOOK.md)** has the exact commands, expected output and a 5–10 minute demo sequence. Observed results and how to interpret them are in **[docs/MEASUREMENT_REPORT.md](docs/MEASUREMENT_REPORT.md)**.

| What | Where |
|---|---|
| Health and probes | `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` |
| Prometheus metrics | `GET /actuator/prometheus`: `http_server_requests_seconds_*` (count, latency histogram, status/outcome) plus `ledger_transactions_total{type,outcome}` and `ledger_api_errors_total{code}` |
| Load test + DB verification | `scripts/load-test.sh` (k6 in Docker, then `scripts/verify-ledger.sh`) |
| Restart / recovery | `scripts/recovery-check.sh` (isolated Compose project; recreates the API and PostgreSQL containers) |

All of this is local validation. It is not a deployed service and not a capacity benchmark.

## Deliberate scope limits

No authentication or multi-tenancy, so idempotency keys are global. In production they would be scoped per client and expired after a retention window. Refunds are full refunds only, and there is no currency conversion. The actuator endpoints are unauthenticated and served on the application port, which suits local use only.
