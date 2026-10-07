# Idempotent Payment Ledger API: Final Project Report

**Project status: COMPLETED**
**Final verification date:** 2026-10-07
**Final result:**
- 33 / 33 integration tests passed, `BUILD SUCCESS`
- k6 load test passed: all checks and thresholds, followed by database ledger verification
- Docker restart/recovery check passed

Everything in this report comes from the code in this repository and from the verification runs recorded in §15 and §19.

| Revision | Date | Scope |
|---|---|---|
| 1.0 | 2026-09-30 | Core API: idempotent payments/refunds, double-entry ledger, DB invariants, 27 integration tests |
| 1.1 | 2026-10-07 | Operational validation: Actuator health/probes, Prometheus metrics with ledger counters, containerized API, k6 load test with a DB verifier, restart/recovery script, 6 more tests, RUNBOOK.md |

---

## 1. Project overview

The Idempotent Payment Ledger API is a REST backend that records money movements between accounts in a double-entry ledger. It is built around two guarantees:

1. **Exactly-once effect per `Idempotency-Key`.** Retries of a request, including concurrent ones, never create a second transaction. This is enforced by a PostgreSQL unique constraint used inside the same database transaction as the ledger writes, not by an in-memory check.
2. **A ledger that always balances.** Every posted transaction writes an equal debit and credit. PostgreSQL itself rejects unbalanced, mutated or overdrawn ledger state.

Features: account creation, payments, full refunds, transaction lookup with ledger entries, the transaction lifecycle (`PENDING`, `POSTED`, `FAILED`, `REFUNDED`), Bean Validation, problem+json error responses, OpenAPI/Swagger documentation, Docker Compose for PostgreSQL and the API, and a JUnit 5 + Testcontainers integration test suite.

Operational layer (§19):
- health and probe endpoints
- Prometheus metrics: HTTP request volume, latency histograms and status/outcome, plus payment-domain counters
- a repeatable k6 load test that verifies correctness and checks the ledger in PostgreSQL afterwards
- a scripted restart/recovery check that replaces the API and PostgreSQL containers

## 2. Architecture and technology stack

### Stack (versions confirmed from `pom.xml` and the build/test logs)

| Concern | Technology | Version |
|---|---|---|
| Language / runtime | Java (Eclipse Temurin) | 21 (tests ran on 21.0.12.1) |
| Framework | Spring Boot (Web MVC, Data JPA, Validation, Actuator) | 3.5.16 |
| ORM | Hibernate ORM | 6.6.53.Final |
| Database | PostgreSQL | 16 (`postgres:16-alpine`, 16.15 at runtime) |
| Migrations | Flyway (+ `flyway-database-postgresql`) | 11.7.2 |
| JDBC driver | PostgreSQL JDBC | 42.7.11 |
| API docs | springdoc-openapi (Swagger UI) | 2.8.17 |
| Tests | JUnit 5, AssertJ, Spring Boot Test, Testcontainers | Testcontainers 1.21.4 |
| Metrics | Micrometer + Prometheus registry (via Spring Boot Actuator) | 1.15.12 |
| Load testing | k6 (Docker image `grafana/k6`) | 1.8.1 |
| Build | Maven via Maven Wrapper | Maven 3.9.11, wrapper 3.3.4 |
| Local infra | Docker Compose (PostgreSQL + API), multi-stage `Dockerfile` | `compose.yaml` |

### Layered architecture

```
HTTP ──► controller/   AccountController, TransactionController
           │           header + @Valid body validation, HTTP status / Idempotent-Replayed header
           ▼
         service/      AccountService, TransactionService (one @Transactional method = one DB transaction)
           │           RequestFingerprint (SHA-256), TransactionResult
           ▼
         repository/   Spring Data JPA repositories + IdempotentInsertRepositoryImpl
           │           (NamedParameterJdbcTemplate: INSERT ... ON CONFLICT DO NOTHING)
           ▼
         domain/       Account, LedgerTransaction, LedgerEntry, enums, Money
           ▼
       PostgreSQL      schema, constraints and triggers owned by Flyway (V1__create_ledger_schema.sql)

cross-cutting: dto/ (request/response records), exception/ (ApiException, GlobalExceptionHandler),
               config/ (OpenAPI metadata), metrics/ (LedgerMetrics: Micrometer counters)
```

Design properties:

- **The domain owns state changes.** Balances change only through `LedgerEntry.transfer(...)`, which debits one account, credits the other and returns the balanced entry pair; `Account.debit/credit` are package-private. Status changes go through guarded methods (`markPosted`, `markFailed`, `markRefunded`) that throw on an illegal transition.
- **The schema is owned by Flyway.** Hibernate runs with `ddl-auto: validate`, so the application refuses to start if the entities and the schema drift apart.
- `open-in-view: false` and `hibernate.jdbc.time_zone: UTC`.

### Size (measured)

| Item | Count |
|---|---|
| Main Java source files | 28 (1,303 lines) |
| Test Java source files | 7 (874 lines) |
| Flyway SQL migration | 1 file (104 lines) |
| Integration test cases | 33 |
| Operational tooling | `load-test/ledger-load.js` (288 lines), 3 bash scripts (307 lines), `Dockerfile`, `compose.yaml` |

## 3. API endpoints

Confirmed at runtime from `/v3/api-docs`: `/accounts`, `/accounts/{id}`, `/transactions`, `/transactions/{id}`, `/transactions/{id}/refund`.

| Method | Path | Header | Success | Description |
|---|---|---|---|---|
| `POST` | `/accounts` | – | `201` + `Location` | Create an account (`name`, `currency`, optional `allowNegativeBalance`) |
| `GET` | `/accounts/{id}` | – | `200` | Account with its current balance |
| `POST` | `/transactions` | `Idempotency-Key` (required) | `201` new · `200` replay | Create a payment |
| `GET` | `/transactions/{id}` | – | `200` | Transaction with its ledger entries |
| `POST` | `/transactions/{id}/refund` | `Idempotency-Key` (required) | `201` new · `200` replay | Fully refund a posted payment |
| `GET` | `/actuator/health` | – | `200` | Health, with component status (`db`, `diskSpace`, …) |
| `GET` | `/actuator/health/liveness`, `/actuator/health/readiness` | – | `200` | Kubernetes-style probes (used by the Compose healthcheck) |
| `GET` | `/actuator/prometheus` | – | `200` | Prometheus-format metrics (added in 1.1) |
| `GET` | `/actuator/metrics[/{name}]` | – | `200` | JSON metric browser (added in 1.1) |

Every create/refund response carries an `Idempotent-Replayed: true|false` header. Monetary values are serialized as strings with four decimal places (`"100.0000"`) so that JSON clients never handle them as floats.

## 4. Idempotency implementation

**Storage.** The key lives on the transaction row itself: `transactions.idempotency_key VARCHAR(255) NOT NULL` with `CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key)`. Next to it, `request_hash` stores a SHA-256 fingerprint of the request.

**Fingerprint** (`RequestFingerprint`). It is computed from the operation type plus the relevant fields: source, destination, the amount normalized to scale 4 (`toPlainString()`), currency and description for payments, and the original transaction id for refunds. Each part is length-prefixed so that field values can't be ambiguously concatenated. As a result:
- `"10"` and `"10.0000"` are the same request (tested).
- Reusing a payment key for a refund is detected, because the operation type is part of the hash (tested).

**Algorithm** (`TransactionService.createPayment`, single `@Transactional` method, READ COMMITTED):

1. **Fast path:** `findByIdempotencyKey`. If a row exists and the fingerprint matches, return it as a replay (`200`). If the fingerprint differs, return `422 IDEMPOTENCY_KEY_REUSED`.
2. Validate that source ≠ destination, then lock both accounts with `SELECT … FOR UPDATE` ordered by id (`404` if missing), then check currency (`422` on mismatch).
3. **Atomic key claim:** `INSERT INTO transactions … ON CONFLICT (idempotency_key) DO NOTHING` (`IdempotentInsertRepositoryImpl`, sharing the JPA transaction's connection).
   - 1 row inserted: this request owns the key.
   - 0 rows: another request owns it. Load that row and replay it (or return 422 on a fingerprint mismatch).
4. Post the ledger entries, or mark the transaction `FAILED`, then commit.

**Semantics:**

| Situation | Result | Key consumed? |
|---|---|---|
| First request | `201`, `POSTED` or `FAILED` | yes |
| Same key + same request | `200`, same transaction, `Idempotent-Replayed: true` | – |
| Same key + different request | `422 IDEMPOTENCY_KEY_REUSED` | – |
| Validation error / unknown account / currency mismatch | `400` / `404` / `422` | no (tested for currency mismatch) |
| Business failure (insufficient funds) | `201` with `status: FAILED`, replayed on retry | yes (tested) |
| Missing / blank header | `400 MISSING_HEADER` / `400 VALIDATION_FAILED` | no |
| Crash before commit | everything rolls back, including the key claim | no |

## 5. Concurrent duplicate-request handling

When N requests with the same key arrive at the same time:

- Same-key requests also target the same accounts, so they serialize on the account row locks. Whichever request gets the locks first inserts, posts and commits. The others acquire the locks afterwards, their `ON CONFLICT` insert affects 0 rows, and they return the committed row as a replay.
- If requests with the same key but *different* accounts race, the account locks don't serialize them. The PostgreSQL unique index does: the second `INSERT … ON CONFLICT` waits for the first transaction to finish, then inserts nothing, and the fingerprint mismatch produces `422`.
- No exception-driven retry is needed. `ON CONFLICT DO NOTHING` doesn't abort the PostgreSQL transaction, so the losing request can keep going and read the winner's row.

**Verified by tests:**

- `concurrentRequestsWithSameKeyCreateExactlyOneTransaction`: 16 threads released together by a `CountDownLatch` send the same payment over real HTTP. Result:
  - all 2xx: exactly one `201` and fifteen `200`
  - a single distinct transaction id
  - 1 row for the key in the DB and 2 ledger entries
  - balances moved exactly once (`975.00` / `25.00`)
- `concurrentRefundsWithSameKeyCreateExactlyOneRefund`: 12 threads send the same refund key. Result: all 2xx, exactly one `201`, one distinct id, exactly one row with that `refund_of_id`, balances fully restored.

## 6. Double-entry ledger and database invariants

**Posting rule.** A transfer of `amount` from account A to account B writes `DEBIT A amount` and `CREDIT B amount`, in the transaction's currency. An account's balance is `Σ credits − Σ debits`. `accounts.balance` is a cached value that is updated in the same database transaction as the entries, under a row lock.

**Enforced by PostgreSQL** (`V1__create_ledger_schema.sql`):

| Invariant | Mechanism |
|---|---|
| Debits = credits per transaction | `trg_ledger_entries_balanced`: `CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED` that runs `assert_transaction_balanced()` at COMMIT |
| Single currency per transaction | same trigger (`COUNT(DISTINCT currency) > 1` is rejected) |
| Ledger is append-only | `trg_ledger_entries_append_only`: `BEFORE UPDATE OR DELETE` trigger raises an error |
| One transaction per idempotency key | `uq_transactions_idempotency_key` UNIQUE |
| At most one non-failed refund per payment | partial unique index `uq_transactions_one_refund_per_payment ON (refund_of_id) WHERE refund_of_id IS NOT NULL AND status <> 'FAILED'` |
| No overdraft on normal accounts | `chk_accounts_non_negative_balance CHECK (allow_negative_balance OR balance >= 0)` |
| Positive amounts | `chk_transactions_amount_positive`, `chk_ledger_entries_amount_positive` |
| Valid enums | `chk_transactions_type`, `chk_transactions_status`, `chk_ledger_entries_direction` |
| Source ≠ destination | `chk_transactions_distinct_accounts` |
| Refund ⇔ `refund_of_id` set | `chk_transactions_refund_link` |
| A refund can't itself be `REFUNDED` | `chk_transactions_refund_not_refunded` |
| Currency format | `chk_accounts_currency_format` (`^[A-Z]{3}$`) |
| Referential integrity | FKs from transactions → accounts / transactions and from entries → transactions / accounts |

**Verified by tests:**

- Direct SQL that inserts an unbalanced entry is rejected at commit with "Unbalanced ledger transaction", and the entry count stays at 2.
- `UPDATE` and `DELETE` on entries are rejected.
- A duplicate key insert violates `uq_transactions_idempotency_key`.
- A negative balance violates `chk_accounts_non_negative_balance`.
- A zero amount violates `chk_transactions_amount_positive`.
- `ledgerIsGloballyBalancedAndBalancesMatchEntries` checks that global Σ debits = Σ credits, and that every account's `balance` equals the sum of its own entries.

## 7. Transaction and refund workflows

### Status lifecycle

```
PAYMENT:  PENDING ──► POSTED ──► REFUNDED
             └──────► FAILED (INSUFFICIENT_FUNDS, no entries)
REFUND:   PENDING ──► POSTED
             └──────► FAILED (INSUFFICIENT_FUNDS, no entries; original stays POSTED)
```

`PENDING` is the status the row is inserted with while the key is claimed. It is finalized to `POSTED` or `FAILED` inside the same database transaction, so clients only observe the final states, and no transaction is ever left stuck in `PENDING`.

### Payment (`POST /transactions`)

Idempotency fast path, then validation, then account locks, then the atomic key claim. After that:
- If the source can't be debited: `FAILED` with `failureReason: INSUFFICIENT_FUNDS` and no entries.
- Otherwise: a balanced DEBIT/CREDIT pair is written, balances are updated and the status becomes `POSTED`.

### Refund (`POST /transactions/{id}/refund`)

1. Idempotency fast path (fingerprint = `REFUND` + original id).
2. `SELECT … FOR UPDATE` on the original transaction (`404 TRANSACTION_NOT_FOUND` if it doesn't exist). This serializes all refund attempts for the same payment.
3. Check the key again after acquiring the lock, which catches a same-key request that committed while this one was waiting.
4. Rules:
   - refund of a refund: `422 TRANSACTION_NOT_REFUNDABLE`
   - already refunded: `409 TRANSACTION_ALREADY_REFUNDED`
   - not `POSTED` (e.g. `FAILED`): `409 TRANSACTION_NOT_REFUNDABLE`
5. Lock both accounts, then claim the key with `ON CONFLICT`, then insert a `REFUND` transaction in the reverse direction for the full amount with `refund_of_id` set.
6. If the original recipient no longer holds the funds, the refund is `FAILED` and the original stays `POSTED`. Otherwise the reversing entries are posted, the refund becomes `POSTED` and the original becomes `REFUNDED`.

## 8. Concurrency and race-condition protection

| Race | Protection | Test evidence |
|---|---|---|
| Duplicate submissions, same key | Unique constraint + `ON CONFLICT DO NOTHING` inside the posting transaction | 16 parallel requests → 1 transaction |
| Concurrent spending from one account | `PESSIMISTIC_WRITE` (`FOR UPDATE`) on account rows before the balance check; DB `CHECK` as a backstop | 20 parallel payments of 10.00 from 100.00 → exactly 10 `POSTED`, 10 `FAILED`, final balance `0`, destination `100.00` |
| Double refund with different keys | `FOR UPDATE` on the original payment + partial unique index as a backstop | 10 parallel refunds → exactly 1 `201`, 9 `409` |
| Double refund with same key | Key re-check after taking the lock + `ON CONFLICT` | 12 parallel requests → 1 refund |
| Deadlock between A→B and B→A transfers | Accounts are always locked in a fixed order (`order by a.id`) | by design (lock ordering) |
| Partial writes on crash | Key claim, entries, balances and status are committed atomically in one DB transaction | by design (single `@Transactional`) |

Accounts are locked *before* the transaction row is inserted. This is deliberate: the foreign key check on insert takes a `FOR KEY SHARE` lock on the account rows, and upgrading that later to `FOR UPDATE` could deadlock against a concurrent request.

## 9. Validation and error handling

**Bean Validation** on request records:

| Field | Constraints |
|---|---|
| `amount` | `@NotNull @Positive @Digits(integer = 15, fraction = 4)` |
| `currency` | `@NotBlank @Pattern("^[A-Z]{3}$")` |
| account ids | `@NotNull UUID` |
| `description` | `@Size(max = 255)` |
| account `name` | `@NotBlank @Size(max = 100)` |
| `Idempotency-Key` header | `@NotBlank @Size(max = 255)` (Spring method validation) |

Amounts are normalized with `setScale(4, RoundingMode.UNNECESSARY)`, so excess precision fails loudly instead of being silently rounded.

**Global error handling.** `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`. Every error is returned as RFC 9457 `application/problem+json` with a stable, machine-readable `code`. Validation errors also include `errors: [{field, message}]`.

| HTTP | `code` values |
|---|---|
| 400 | `VALIDATION_FAILED`, `MISSING_HEADER`, `SAME_ACCOUNT`, `REQUEST_ERROR` (malformed JSON, etc.) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND` |
| 409 | `TRANSACTION_ALREADY_REFUNDED`, `TRANSACTION_NOT_REFUNDABLE`, `CONSTRAINT_VIOLATION` (DB constraint backstop), `CONCURRENT_MODIFICATION` (lock failure, safe to retry) |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_MISMATCH`, `TRANSACTION_NOT_REFUNDABLE` (refund of a refund) |
| 500 | `INTERNAL_ERROR` (details logged, not leaked) |

Tested responses include: negative amount, 5-decimal amount, lowercase currency, missing fields, same source and destination, missing key, blank key, unknown account, currency mismatch, key reuse, double refund, refund of a failed payment, refund of a refund, and refund of an unknown id.

## 10. PostgreSQL and Flyway

- A single migration, `V1__create_ledger_schema.sql`, creates 3 tables (`accounts`, `transactions`, `ledger_entries`), 11 named CHECK/UNIQUE constraints (plus foreign keys), 1 partial unique index, 2 secondary indexes (`ledger_entries.transaction_id`, `ledger_entries.account_id`), 2 PL/pgSQL functions and 2 triggers.
- Money is stored as `NUMERIC(19,4)`, timestamps as `TIMESTAMPTZ`, and ids as `UUID`.
- Flyway applies the migration on startup (both runs logged "Successfully applied 1 migration … now at version v1"), and Hibernate `validate` confirms the entities match the schema.
- Connection settings can be overridden with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. The defaults point at the Compose database (`jdbc:postgresql://localhost:5432/ledger`, user/password `ledger`).

## 11. Docker setup

`compose.yaml` defines the project `idempotent-payment-ledger` with two services:

| Service | Image | Details |
|---|---|---|
| `postgres` | `postgres:16-alpine` | db/user/password `ledger`; named volume `ledger-data`; `pg_isready` healthcheck; published on `127.0.0.1:${POSTGRES_PORT:-5432}` |
| `api` | built from the multi-stage `Dockerfile` | build stage `eclipse-temurin:21-jdk` (Maven wrapper, BuildKit cache for `~/.m2`); runtime stage `eclipse-temurin:21-jre` as the non-root `ubuntu` user; waits for a healthy Postgres; healthcheck on `/actuator/health/readiness`; published on `127.0.0.1:${API_PORT:-8080}` |

- Both ports bind to localhost only.
- `docker compose up -d --build --wait` returns once both healthchecks pass.
- `docker compose up -d postgres` still supports running the API on the host.
- Verified on 2026-10-07: both services reached `(healthy)`, and `/actuator/health` reported `db: UP`. Details are in §19.

## 12. JUnit 5 and Testcontainers testing

- **Setup:** `TestcontainersConfiguration` declares a `PostgreSQLContainer("postgres:16-alpine")` bean with `@ServiceConnection`, so Spring Boot wires the datasource automatically. The container is shared across test classes through Spring's context cache.
- **Style:** full-stack integration tests. `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate` sends real HTTP requests to the real application against a real PostgreSQL. `JdbcTemplate` is used to assert database state directly.
- **Isolation:** each test creates its own accounts and random idempotency keys, so no cleanup is needed, and the append-only trigger never has to be bypassed.
- **Concurrency harness:** `runConcurrently(n, task)` uses a fixed thread pool plus ready/go `CountDownLatch`es, so all requests are released at the same moment.

### Test inventory (33 tests)

**`IdempotencyIntegrationTest` (9)**
- `duplicateRequestWithSameKeyReturnsOriginalTransactionWithoutPostingTwice`
- `numericallyEqualAmountsAreTreatedAsTheSameRequest`
- `reusingKeyWithDifferentPayloadIsRejected`
- `reusingPaymentKeyForRefundIsRejected`
- `missingIdempotencyKeyIsRejected`
- `blankIdempotencyKeyIsRejected`
- `failedTransactionIsAlsoReplayedForTheSameKey`
- `concurrentRequestsWithSameKeyCreateExactlyOneTransaction`
- `concurrentRefundsWithSameKeyCreateExactlyOneRefund`

**`TransactionLedgerIntegrationTest` (12)**
- `paymentPostsBalancedDebitAndCreditEntries`
- `insufficientFundsMarksTransactionFailedWithoutEntries`
- `currencyMismatchIsRejectedAndDoesNotConsumeKey`
- `unknownAccountReturns404`
- `invalidRequestBodiesAreRejectedWithFieldErrors`
- `refundReversesEntriesAndMarksPaymentRefunded`
- `secondRefundWithDifferentKeyIsRejected`
- `failedPaymentsAndRefundsCannotBeRefunded`
- `refundFailsWhenRecipientNoLongerHoldsTheFunds`
- `concurrentRefundsWithDifferentKeysRefundExactlyOnce`
- `concurrentPaymentsNeverOverdrawAnAccount`
- `ledgerIsGloballyBalancedAndBalancesMatchEntries`

**`DatabaseConstraintsIntegrationTest` (6)**
- `unbalancedEntriesAreRejectedAtCommit`
- `ledgerEntriesAreAppendOnly`
- `idempotencyKeyIsUniqueAtTheDatabaseLevel`
- `nonOverdraftAccountBalanceCannotGoNegative`
- `nonPositiveAmountsAreRejected`
- `openApiDocumentIsServed`

**`MetricsIntegrationTest` (5)**, added in 1.1. It runs with `@AutoConfigureObservability(tracing = false)`, because Spring Boot disables metrics export in tests by default.
- `paymentOutcomesAreCountedByOutcome`
- `refundOutcomesAreCounted`
- `concurrentSameKeyBurstCountsOnePostedAndTheRestReplayed` (10 threads: `posted` +1, `replayed` +9)
- `prometheusEndpointExposesHttpAndLedgerMetricsWithoutSensitiveValues`
- `healthEndpointReportsDatabaseAndProbes`

**`ApplicationRestartIntegrationTest` (1)**, added in 1.1
- `idempotencyKeyAndLedgerSurviveAnApplicationRestart`: one standalone application instance creates a payment and is shut down; a new instance against the same database replays the request and gets the same id, still 1 row and 2 entries.

## 13. OpenAPI / Swagger

- springdoc-openapi 2.8.17. An `OpenApiConfig` bean provides the title, version and description. Controllers are annotated with `@Tag`, `@Operation`, `@ApiResponse` and `@Parameter` (the `Idempotency-Key` is documented as required), and DTOs with `@Schema` examples.
- Swagger UI is at `http://localhost:8080/swagger-ui.html` and the OpenAPI JSON at `http://localhost:8080/v3/api-docs`.
- Verified at runtime: both returned HTTP `200`, and the document lists all five API paths. The `openApiDocumentIsServed` test also asserts that `/transactions` and `/transactions/{id}/refund` are present.

## 14. Important engineering decisions

1. **Idempotency in the database, in the same transaction.** Putting the key claim, ledger entries, balance updates and final status in one commit rules out both "key stored but money not moved" and "money moved but key lost". A separate idempotency table or cache would have needed a two-phase protocol and recovery for stuck `PENDING` records.
2. **`INSERT … ON CONFLICT DO NOTHING` instead of catching the unique violation.** In PostgreSQL a failed statement aborts the whole transaction. `ON CONFLICT` lets the losing request continue in the same transaction and return the winner's result.
3. **Request fingerprinting.** Reusing a key for a different request is a client bug and is rejected with `422`, rather than silently returning an unrelated transaction.
4. **Business failures are recorded, validation failures are not.** Insufficient funds produces a durable `FAILED` transaction that replays consistently. Malformed or invalid requests don't consume the key, so the client can fix the request and retry.
5. **Pessimistic locking with deterministic ordering.** Balance checks are only correct under a lock. Ordering locks by account id prevents deadlocks between opposite-direction transfers. Locking accounts before the insert avoids the FK `KEY SHARE` → `FOR UPDATE` lock-upgrade deadlock.
6. **The database as the last line of defense.** The deferred balance trigger, the append-only trigger, the CHECK constraints and the partial unique index hold even if application code is wrong or someone writes SQL directly. The tests confirm this by bypassing the application.
7. **Refunds are reversing transactions, not edits.** The ledger is append-only. A refund is a new `REFUND` transaction linked by `refund_of_id`, and the original moves to `REFUNDED`.
8. **Exact money handling.** `BigDecimal` throughout, `NUMERIC(19,4)` in the database, scale normalization with `RoundingMode.UNNECESSARY`, and string serialization in JSON.
9. **Integration tests over mocks.** Idempotency and concurrency correctness depend on real PostgreSQL behavior (index waits, row locks, deferred triggers), so they are tested against a real PostgreSQL via Testcontainers.

## 15. Exact final test and build results

**Command (final run, 2026-10-07):**

```
./mvnw -B clean verify
```

Run in an `eclipse-temurin:21-jdk` container with the Docker socket mounted, so Testcontainers could start PostgreSQL. The host machine has no local JDK.

**Output (verbatim excerpts):**

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.028 s -- in io.github.omerbar4.paymentledger.MetricsIntegrationTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.282 s -- in io.github.omerbar4.paymentledger.TransactionLedgerIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.233 s -- in io.github.omerbar4.paymentledger.DatabaseConstraintsIntegrationTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.348 s -- in io.github.omerbar4.paymentledger.IdempotencyIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.897 s -- in io.github.omerbar4.paymentledger.ApplicationRestartIntegrationTest
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: /app/target/idempotent-payment-ledger-1.0.0.jar
[INFO] BUILD SUCCESS
[INFO] Total time:  11.793 s
[INFO] Finished at: 2026-10-07T05:46:38Z
```

**Results:**

| Metric | Result |
|---|---|
| Tests run | **33** (the 27 pre-existing tests are unchanged, plus 6 new ones) |
| Failures / Errors / Skipped | **0 / 0 / 0** |
| Build | **BUILD SUCCESS** (exit code 0) |
| Artifact | `target/idempotent-payment-ledger-1.0.0.jar` (62,935,349 bytes, executable Spring Boot jar) |
| Test database | PostgreSQL 16 via Testcontainers 1.21.4 |
| Application ERROR/WARN log lines during the test run | 0 (excluding springdoc's informational "enabled by default" notices and the JDK's Mockito agent notice) |

**Baseline before the 1.1 changes.** The same command was run on the unmodified repository on 2026-10-07: `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

**Historical (1.0, 2026-09-30).** The concurrency test classes were rerun 3 extra times, and all passed each time. The packaged jar was smoke-tested against Compose PostgreSQL with curl:
- first payment `201`, replay `200`, key reuse `422`
- refund `201`, after which the payment showed `REFUNDED` and the balances were restored
- missing key `400`
- Swagger UI and `/v3/api-docs` returned `200`

The 1.1 runs in §19 supersede this as the current runtime evidence.

## 16. Project structure

```
.
├── FINAL_PROJECT_REPORT.md
├── README.md
├── RUNBOOK.md                           # run / observe / load-test / restart-test / demo guide
├── Dockerfile, .dockerignore            # multi-stage API image (JDK build -> JRE runtime, non-root)
├── compose.yaml                         # project-scoped: postgres + api, ports on 127.0.0.1
├── load-test/ledger-load.js             # k6 scenarios: payments, idempotency burst, refunds
├── load-test/results/                   # k6 output (git-ignored)
├── scripts/load-test.sh                 # k6 in Docker + DB verification
├── scripts/verify-ledger.sh             # ledger invariants checked in PostgreSQL
├── scripts/recovery-check.sh            # API + PostgreSQL container restart/durability check
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/wrapper/        # Maven Wrapper (Maven 3.9.11)
└── src
    ├── main
    │   ├── java/io/github/omerbar4/paymentledger
    │   │   ├── LedgerApplication.java
    │   │   ├── config/OpenApiConfig.java
    │   │   ├── controller/{AccountController, TransactionController}.java
    │   │   ├── domain/{Account, LedgerTransaction, LedgerEntry, Money,
    │   │   │           TransactionStatus, TransactionType, EntryDirection}.java
    │   │   ├── dto/{CreateAccountRequest, AccountResponse, CreateTransactionRequest,
    │   │   │        TransactionResponse, LedgerEntryResponse}.java
    │   │   ├── exception/{ApiException, GlobalExceptionHandler}.java
    │   │   ├── metrics/LedgerMetrics.java
    │   │   ├── repository/{AccountRepository, LedgerTransactionRepository, LedgerEntryRepository,
    │   │   │               IdempotentInsertRepository, IdempotentInsertRepositoryImpl}.java
    │   │   └── service/{AccountService, TransactionService, RequestFingerprint,
    │   │                TransactionResult}.java
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/V1__create_ledger_schema.sql
    └── test/java/io/github/omerbar4/paymentledger
        ├── TestcontainersConfiguration.java
        ├── AbstractIntegrationTest.java
        ├── IdempotencyIntegrationTest.java
        ├── TransactionLedgerIntegrationTest.java
        ├── DatabaseConstraintsIntegrationTest.java
        ├── MetricsIntegrationTest.java
        └── ApplicationRestartIntegrationTest.java
```

## 17. Known limitations

These are intentional scope boundaries of the finished project:

- **No authentication or multi-tenancy.** Idempotency keys are global rather than scoped per client, and they have no expiry or retention window.
- **Full refunds only.** No partial refunds, and at most one successful refund per payment.
- **Single currency per transaction.** No FX conversion; the transaction currency must match both accounts.
- **Replays return current state.** A replayed request returns the transaction as it is now (for example `REFUNDED`), not a byte-for-byte copy of the original response.
- **`PENDING` is internal only.** It exists only inside the creating database transaction and isn't observable through the API.
- **No idempotency for account creation.** `POST /accounts` doesn't take an idempotency key.
- **No listing or pagination endpoints.** Transactions and accounts can only be retrieved by id.
- **Integration tests only.** All 33 tests are full-stack integration tests; there is no separate unit-test layer.
- **Operational validation is local only** (§19.4):
  - Load-test numbers come from one laptop, one API instance and one PostgreSQL container. They show correctness under concurrency, not capacity.
  - No multi-instance or load-balanced setup was tested.
  - Metrics are exposed for scraping, but no Prometheus server, dashboards or alerts are included.
  - Actuator endpoints are unauthenticated and share the application port.
- **Package naming.** The Java package and Maven `groupId` are `io.github.omerbar4.paymentledger`.

## 18. Résumé evidence

| Claim | Evidence in code | Evidence in tests |
|---|---|---|
| DB-level, concurrency-safe idempotency | `uq_transactions_idempotency_key`; `IdempotentInsertRepositoryImpl` (`ON CONFLICT DO NOTHING`); `TransactionService.createPayment` / `refund` | `concurrentRequestsWithSameKeyCreateExactlyOneTransaction` (16 threads → 1 txn, 2 entries); `concurrentRefundsWithSameKeyCreateExactlyOneRefund` (12 threads → 1 refund) |
| Key-reuse detection | `RequestFingerprint` (SHA-256), `request_hash` column | `reusingKeyWithDifferentPayloadIsRejected`, `reusingPaymentKeyForRefundIsRejected` |
| Double-entry ledger invariant enforced by PostgreSQL | `trg_ledger_entries_balanced` (deferred), `trg_ledger_entries_append_only` | `unbalancedEntriesAreRejectedAtCommit`, `ledgerEntriesAreAppendOnly`, `ledgerIsGloballyBalancedAndBalancesMatchEntries` |
| No overdrafts under concurrency | `findAllByIdForUpdate` (ordered `FOR UPDATE`), `chk_accounts_non_negative_balance` | `concurrentPaymentsNeverOverdrawAnAccount` (20 threads → 10 posted / 10 failed, balance 0) |
| Exactly-once refunds | `findByIdForUpdate` on the original, partial unique index | `concurrentRefundsWithDifferentKeysRefundExactlyOnce` (10 threads → 1 × 201, 9 × 409) |
| Testcontainers integration suite | `TestcontainersConfiguration`, `AbstractIntegrationTest` | 33 / 33 passing against PostgreSQL 16 |
| HTTP + domain metrics without sensitive tags | `LedgerMetrics`, `application.yml` (`management.*`) | `MetricsIntegrationTest` (5 tests); live scrape in §19 |
| Idempotency survives API and DB restarts | key stored in PostgreSQL; named volume | `ApplicationRestartIntegrationTest`; `scripts/recovery-check.sh` run (§19) |
| Same-key burst under load → one transaction | as above | k6 `idempotency_burst` (20 concurrent → 1 × 201, 19 × 200, 1 id) + `verify-ledger.sh` |

### Strongest résumé bullets

- **Built an idempotent payment ledger REST API in Java 21, Spring Boot 3.5 and PostgreSQL.** Idempotency is enforced at the database level with a unique constraint and `INSERT … ON CONFLICT DO NOTHING` in the same transaction as the ledger writes; verified that 16 concurrent requests with the same key produce exactly one transaction.
- **Enforced double-entry accounting invariants in PostgreSQL** with a deferred constraint trigger (debits = credits at commit), an append-only ledger trigger, and CHECK/partial-unique constraints. Pessimistic row locks taken in a fixed order prevent overdrafts and double refunds under concurrent load.
- **Wrote 33 JUnit 5 + Testcontainers integration tests** against real PostgreSQL covering duplicate and concurrent idempotency keys, race conditions (20 parallel payments never overdraw an account; 10 parallel refunds succeed exactly once), validation, RFC 9457 error handling, and database-level constraint enforcement.

### Optional additional wording

*Supported only after the listed commands pass.* All three commands passed on 2026-10-07 (§19); re-run them before relying on this wording.

- Added operational validation to the ledger API: Micrometer/Prometheus metrics (HTTP latency histograms, status outcomes, transaction-outcome counters) and a k6 load test. In the test, 20 concurrent same-key requests produced exactly one transaction, confirmed by a PostgreSQL ledger-invariant check. A Docker restart script showed that idempotent replays and balanced postings survive API and database container replacement.
  - Commands: `./mvnw clean verify`, `scripts/load-test.sh`, `scripts/recovery-check.sh`.

## 19. Operational validation (1.1)

### 19.1 Implemented

| Area | Implementation |
|---|---|
| Health | `/actuator/health` (shows component status: `db`, `diskSpace`, …; no details), `/actuator/health/liveness`, `/actuator/health/readiness` |
| Metrics | `/actuator/prometheus` (Prometheus text format) and `/actuator/metrics`. HTTP: `http_server_requests_seconds_{count,sum,max,bucket}` tagged `method`, templated `uri`, `status`, `outcome`, `exception`; histogram bounded to 1 ms–5 s. Common tag `application`. |
| Domain counters | `LedgerMetrics`: `ledger_transactions_total{type=payment\|refund, outcome=posted\|failed\|replayed}`, incremented in the controller after the service's transaction commits; `ledger_api_errors_total{code}`, incremented by `GlobalExceptionHandler` for every problem+json response. All tag values come from fixed sets; there are no ids, keys or amounts. |
| Log hygiene | The constraint-violation warning logs only the first line of the PostgreSQL message. The `Detail:` line, which echoes row values such as idempotency keys, is dropped. |
| Container | Multi-stage `Dockerfile`; `api` service in Compose with a readiness healthcheck; localhost-only ports |
| Load test | `load-test/ledger-load.js` (k6 1.8.1, via `scripts/load-test.sh`). Scenarios: `payments`, `idempotency_burst` (`http.batch` of identical same-key requests), `refunds`. Response bodies are checked, not only status codes. Thresholds: checks 100 %, `http_req_failed` 0 %, burst = 1 created / N−1 replayed / 1 id, payment p95 < 1000 ms |
| DB verifier | `scripts/verify-ledger.sh [key]`: psql via `docker compose exec`, with the key passed as a psql variable. Checks the key's single row and balanced pair, plus global invariants: no duplicate keys, no unbalanced transactions, 2 entries per posted/refunded transaction, none for failed ones, no `PENDING`, balances = entries, Σ debits = Σ credits |
| Restart check | `scripts/recovery-check.sh`: isolated Compose project `ledger-recovery-check` (ports 18080/55433). Recreates the API container, then the PostgreSQL container (same volume), asserting at each step. Prints diagnostics on failure and removes only its own project's resources |

The public business API is unchanged; the only additions are read-only actuator endpoints. The service, repositories, domain model and migration are unchanged. Two existing classes were touched:
- `TransactionController` now receives `LedgerMetrics` and records the committed result.
- `GlobalExceptionHandler` now counts error codes and logs only the first line of constraint messages.

### 19.2 Commands run and observed results (2026-10-07)

| # | Command | Observed |
|---|---|---|
| 1 | `./mvnw -B clean verify` (before changes) | 27 run, 0 failures / 0 errors / 0 skipped, `BUILD SUCCESS` |
| 2 | `./mvnw -B clean verify` (final) | 33 run, 0 / 0 / 0, `BUILD SUCCESS` (§15) |
| 3 | `POSTGRES_PORT=55432 docker compose up -d --build --wait` | `postgres` and `api` both `(healthy)`. 5432 was occupied by an unrelated local project, hence the override |
| 4 | `curl localhost:8080/actuator/health` | `{"status":"UP", … "db":{"status":"UP"} …}`; readiness `{"status":"UP"}` |
| 5 | `curl localhost:8080/actuator/prometheus` | HTTP 200, `text/plain;version=0.0.4`; `http_server_requests_seconds_*` series per templated URI/status; `ledger_*` counters (snapshot below); no load-test key found in the scrape |
| 6 | `scripts/load-test.sh` (defaults) | k6 exit 0, verifier exit 0 (output below) |
| 7 | `/bin/bash scripts/recovery-check.sh` | every step PASS, exit 0, 28 s; no `ledger-recovery-check` containers or volumes left afterwards |
| 8 | `RECOVERY_API_PORT=8080 /bin/bash scripts/recovery-check.sh` (deliberate port clash) | exit 1, names the failing step, prints the Docker error and container logs, cleans up; the main stack stayed healthy |
| 9 | `RECOVERY_PROJECT=idempotent-payment-ledger /bin/bash scripts/recovery-check.sh` (pre-commit review fix) | exit 2: refuses any project name not starting with `ledger-recovery-check`, because the script runs `down -v` on that project; existing volumes untouched. Full recovery check re-run afterwards: exit 0, 0 FAIL lines, no leftovers |

**Load test, final run with defaults** (`VUS=5 DURATION=30s SLEEP_SECONDS=0.1 BURST_SIZE=20 REFUND_ITERATIONS=5`):

```
HTTP requests   total=1536  rate=51.0/s
                succeeded=1536  failed(unexpected status)=0
Latency, all    avg=9.10ms  p50=8.18ms  p(90)=15.22ms  p(95)=18.71ms  p(99)=35.11ms  max=100.10ms
Latency, payments scenario  avg=9.17ms  p50=8.21ms  p(90)=15.21ms  p(95)=18.88ms  p(99)=36.11ms  max=100.10ms
Payments created (all checks passed): 1346
Payment retries replayed:             135
Refund cycles completed:              5 / 5
Idempotency burst: 20 concurrent identical requests, one key
                   201 created=1  200 replayed=19  other=0  distinct transaction ids=1  => PASS
Checks: 5824 passed, 0 failed
Overall: PASS        (all 7 thresholds PASS)

Key-specific checks:  1 transaction for the key, POSTED, 2 entries, debits = credits = 25.0000   (all PASS)
Global checks (30994 transactions, 61988 ledger entries): every invariant PASS
Ledger verification: PASS
```

An earlier run used no think time (`SLEEP_SECONDS` didn't exist yet). It passed all checks and thresholds as well: 32,647 requests, 0 failed, p95 12.04 ms, burst 1/19/1, ledger verification PASS. Because it generated about 30k rows per run, the default was changed to a 0.1 s pause per iteration. The database totals above include both runs: 29,637 + 1,357 = 30,994 transactions.

**Metrics reconcile with the database.** The cumulative counters after both runs:

```
ledger_transactions_total{outcome="posted",type="payment"} 30984.0
ledger_transactions_total{outcome="posted",type="refund"} 10.0
ledger_transactions_total{outcome="replayed",type="payment"} 3139.0
ledger_transactions_total{outcome="replayed",type="refund"} 10.0
ledger_transactions_total{outcome="failed",…} 0.0
ledger_api_errors_total{code="TRANSACTION_ALREADY_REFUNDED"} 10.0
http_server_requests_seconds_count{method="POST",…status="201",uri="/transactions"} 30984
```

Posted payments plus posted refunds (30,984 + 10) equal the 30,994 transaction rows counted by `verify-ledger.sh`. The replay count (3,139) equals the 3,101 k6 retries (2,966 + 135) plus 38 burst replays (19 per run).

**Restart/recovery run** (abridged; every line printed `PASS`):

```
postgres and api containers are healthy
POST /transactions -> 201, status POSTED, Idempotent-Replayed: false
[DB] 1 transaction for the key, POSTED, 2 entries, debits = credits = 75.5000; global invariants hold
api container replaced (f00de5f483ee -> f608574951d5) and healthy
POST with the same key -> 200, Idempotent-Replayed: true, same transaction id
GET /transactions/{id} -> POSTED with 2 ledger entries
[DB] still 1 transaction / 2 entries for the key
postgres container replaced (c584531c4be2 -> 3579d132754d) and healthy
API reconnected to the new PostgreSQL container without being restarted
POST with the same key -> 200 replay of the same transaction
new payment with a new key -> 201 POSTED (writes work after the restart)
customer balance is 76.5000 (75.50 once, not twice, plus 1.00)
[DB] 2 transactions, 4 entries, every invariant PASS
Recovery check PASSED
```

### 19.3 Same-key duplicate protection: final check

Three independent sources confirm that a duplicate key produces one transaction and one balanced posting:
- the automated suite: `IdempotencyIntegrationTest` with 16 threads, and `MetricsIntegrationTest` with 10 threads (+1 posted, +9 replayed)
- the k6 burst (20 concurrent requests) followed by `verify-ledger.sh` on that key: 1 row, 2 entries, 25.0000 = 25.0000
- the recovery check, where the same key was replayed across both container replacements and stayed at 1 row and 2 entries

### 19.4 Not verified / out of scope

- **Capacity and production behaviour.** No sustained, soak or multi-instance testing was done, and no production-like hardware was used. The latency figures apply to this laptop only.
- **Observability backend.** There is no Prometheus server, Grafana or alerting. Percentiles in this report come from k6; the exported histogram buckets were checked for presence, not queried through PromQL.
- **Durability boundary.** PostgreSQL was restarted with Docker's normal stop, which shuts down cleanly. No hard kill, crash or `fsync`-loss scenario was tested.
- **Restart coverage.** The scripted restarts replace one container at a time. No in-flight requests were interrupted mid-transaction by a restart (the atomic-commit design covers that case, but it wasn't exercised here).
- **Linux.** The scripts were run on macOS with `/bin/bash` 3.2 and Docker Desktop. They are written for Linux too (`host-gateway` mapping, no GNU-only flags), but were not executed there.

---

## Final Verification Summary

| # | Functionality | Verified by | Result |
|---|---|---|---|
| 1 | Application compiles and packages (`clean verify`) | Maven build | **PASS** |
| 2 | Flyway migration applies; Hibernate schema validation | Test run + runtime startup logs | **PASS** |
| 3 | `POST /transactions` creates a posted payment with balanced entries | `paymentPostsBalancedDebitAndCreditEntries`, smoke test | **PASS** |
| 4 | `Idempotency-Key` required (missing / blank rejected) | `missingIdempotencyKeyIsRejected`, `blankIdempotencyKeyIsRejected`, smoke test | **PASS** |
| 5 | Duplicate key returns the original transaction, no duplicate posting | `duplicateRequestWithSameKeyReturnsOriginalTransactionWithoutPostingTwice`, smoke test | **PASS** |
| 6 | Key reuse with a different payload rejected (422) | `reusingKeyWithDifferentPayloadIsRejected`, `reusingPaymentKeyForRefundIsRejected`, smoke test | **PASS** |
| 7 | Concurrent same-key payments → exactly one transaction | `concurrentRequestsWithSameKeyCreateExactlyOneTransaction` | **PASS** |
| 8 | Concurrent same-key refunds → exactly one refund | `concurrentRefundsWithSameKeyCreateExactlyOneRefund` | **PASS** |
| 9 | Double-entry invariant enforced by DB at commit | `unbalancedEntriesAreRejectedAtCommit` | **PASS** |
| 10 | Global ledger balance; account balances match entries | `ledgerIsGloballyBalancedAndBalancesMatchEntries` | **PASS** |
| 11 | Append-only ledger entries | `ledgerEntriesAreAppendOnly` | **PASS** |
| 12 | DB constraints (unique key, non-negative balance, positive amount) | `DatabaseConstraintsIntegrationTest` | **PASS** |
| 13 | Insufficient funds → `FAILED`, no entries, replayable | `insufficientFundsMarksTransactionFailedWithoutEntries`, `failedTransactionIsAlsoReplayedForTheSameKey` | **PASS** |
| 14 | Refund reverses entries; payment → `REFUNDED` | `refundReversesEntriesAndMarksPaymentRefunded`, smoke test | **PASS** |
| 15 | Refund rules (double, failed, refund-of-refund, unknown, insufficient recipient funds) | `secondRefundWithDifferentKeyIsRejected`, `failedPaymentsAndRefundsCannotBeRefunded`, `refundFailsWhenRecipientNoLongerHoldsTheFunds` | **PASS** |
| 16 | Concurrent refunds with different keys → exactly one succeeds | `concurrentRefundsWithDifferentKeysRefundExactlyOnce` | **PASS** |
| 17 | No overdraft under concurrent payments | `concurrentPaymentsNeverOverdrawAnAccount` | **PASS** |
| 18 | Bean Validation with field-level errors | `invalidRequestBodiesAreRejectedWithFieldErrors` | **PASS** |
| 19 | Global problem+json error handling with stable codes (400/404/409/422) | Multiple tests + smoke test | **PASS** |
| 20 | Currency mismatch rejected without consuming the key | `currencyMismatchIsRejectedAndDoesNotConsumeKey` | **PASS** |
| 21 | `BigDecimal` / `NUMERIC(19,4)` money handling, 4-dp precision | Code audit + `paymentPostsBalancedDebitAndCreditEntries` (`12.3456`) | **PASS** |
| 22 | OpenAPI document and Swagger UI served | `openApiDocumentIsServed`, runtime `200`/`200` | **PASS** |
| 23 | Docker Compose PostgreSQL starts healthy and the app runs against it | Runtime smoke test | **PASS** |
| 24 | JUnit 5 + Testcontainers suite | 33 run, 0 failures, 0 errors, 0 skipped | **PASS** |
| 25 | Health endpoint with db component, liveness and readiness probes | `healthEndpointReportsDatabaseAndProbes`; live `curl` | **PASS** |
| 26 | Prometheus metrics: HTTP count, latency histogram, status/outcome; no sensitive values | `prometheusEndpointExposesHttpAndLedgerMetricsWithoutSensitiveValues`; live scrape | **PASS** |
| 27 | Ledger counters accurate (posted / failed / replayed, error codes), including under concurrency | `MetricsIntegrationTest` (3 counter tests); counters reconcile with DB row count | **PASS** |
| 28 | Containerized API: Compose stack starts healthy | `docker compose up -d --build --wait` | **PASS** |
| 29 | k6 load test: response correctness, 0 unexpected statuses, latency percentiles reported | `scripts/load-test.sh` (5,824 / 5,824 checks, 7 / 7 thresholds) | **PASS** |
| 30 | Concurrent same-key burst → exactly one transaction, no duplicate ledger entries | k6 burst (1 × 201, 19 × 200, 1 id) + `verify-ledger.sh` key checks | **PASS** |
| 31 | Ledger invariants hold after load (balanced, balances = entries, no PENDING) | `verify-ledger.sh` global checks | **PASS** |
| 32 | Idempotent replay survives application restart (automated) | `ApplicationRestartIntegrationTest` | **PASS** |
| 33 | Replay and ledger survive API container replacement, then PostgreSQL container replacement; API reconnects | `scripts/recovery-check.sh` | **PASS** |
| 34 | Recovery script fails loudly and cleans up only its own resources | Deliberate port-clash run (exit 1 + diagnostics, no leftovers, main stack untouched) | **PASS** |

**Overall project status: COMPLETED**
