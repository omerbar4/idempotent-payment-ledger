# Idempotent Payment Ledger API: Final Project Report

**Project status: COMPLETED**
**Final verification date:** 2026-09-30
**Final result:** 27 / 27 integration tests passed · `BUILD SUCCESS` · runtime smoke test against Docker Compose PostgreSQL passed

Everything in this report comes from the code in this repository and from the verification runs recorded in §15.

---

## 1. Project overview

The Idempotent Payment Ledger API is a REST backend that records money movements between accounts in a double-entry ledger. It is built around two guarantees:

1. **Exactly-once effect per `Idempotency-Key`.** Retries of a request, including concurrent ones, never create a second transaction. This is enforced by a PostgreSQL unique constraint used inside the same database transaction as the ledger writes, not by an in-memory check.
2. **A ledger that always balances.** Every posted transaction writes an equal debit and credit. PostgreSQL itself rejects unbalanced, mutated or overdrawn ledger state.

Features: account creation, payments, full refunds, transaction lookup with ledger entries, the transaction lifecycle (`PENDING`, `POSTED`, `FAILED`, `REFUNDED`), Bean Validation, problem+json error responses, OpenAPI/Swagger documentation, Docker Compose for PostgreSQL, and a JUnit 5 + Testcontainers integration test suite.

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
| Build | Maven via Maven Wrapper | Maven 3.9.11, wrapper 3.3.4 |
| Local infra | Docker Compose | `compose.yaml` |

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
               config/ (OpenAPI metadata)
```

Design properties:

- **The domain owns state changes.** Balances change only through `LedgerEntry.transfer(...)`, which debits one account, credits the other and returns the balanced entry pair; `Account.debit/credit` are package-private. Status changes go through guarded methods (`markPosted`, `markFailed`, `markRefunded`) that throw on an illegal transition.
- **The schema is owned by Flyway.** Hibernate runs with `ddl-auto: validate`, so the application refuses to start if the entities and the schema drift apart.
- `open-in-view: false` and `hibernate.jdbc.time_zone: UTC`.

### Size (measured)

| Item | Count |
|---|---|
| Main Java source files | 27 (1,215 lines) |
| Test Java source files | 5 (633 lines) |
| Flyway SQL migration | 1 file (104 lines) |
| Integration test cases | 27 |

## 3. API endpoints

Confirmed at runtime from `/v3/api-docs`: `/accounts`, `/accounts/{id}`, `/transactions`, `/transactions/{id}`, `/transactions/{id}/refund`.

| Method | Path | Header | Success | Description |
|---|---|---|---|---|
| `POST` | `/accounts` | – | `201` + `Location` | Create an account (`name`, `currency`, optional `allowNegativeBalance`) |
| `GET` | `/accounts/{id}` | – | `200` | Account with its current balance |
| `POST` | `/transactions` | `Idempotency-Key` (required) | `201` new · `200` replay | Create a payment |
| `GET` | `/transactions/{id}` | – | `200` | Transaction with its ledger entries |
| `POST` | `/transactions/{id}/refund` | `Idempotency-Key` (required) | `201` new · `200` replay | Fully refund a posted payment |
| `GET` | `/actuator/health` | – | `200` | Health check |

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

`compose.yaml` runs one `postgres:16-alpine` service (`ledger-postgres`) with:
- database, user and password all set to `ledger`
- a named volume `ledger-data`
- a `pg_isready` healthcheck, so `docker compose up -d --wait` blocks until PostgreSQL is ready
- a host port of `${POSTGRES_PORT:-5432}`, configurable in case 5432 is already in use

Verified: `docker compose up -d --wait` reported the container `Healthy`. The packaged jar connected to it, Flyway migrated it, and the application started ("Started LedgerApplication in 2.388 seconds").

## 12. JUnit 5 and Testcontainers testing

- **Setup:** `TestcontainersConfiguration` declares a `PostgreSQLContainer("postgres:16-alpine")` bean with `@ServiceConnection`, so Spring Boot wires the datasource automatically. The container is shared across test classes through Spring's context cache.
- **Style:** full-stack integration tests. `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate` sends real HTTP requests to the real application against a real PostgreSQL. `JdbcTemplate` is used to assert database state directly.
- **Isolation:** each test creates its own accounts and random idempotency keys, so no cleanup is needed, and the append-only trigger never has to be bypassed.
- **Concurrency harness:** `runConcurrently(n, task)` uses a fixed thread pool plus ready/go `CountDownLatch`es, so all requests are released at the same moment.

### Test inventory (27 tests)

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

**Command:**

```
./mvnw -B clean verify
```

Run in an `eclipse-temurin:21-jdk` container with the Docker socket mounted, so Testcontainers could start PostgreSQL. The host machine has no local JDK.

**Output (verbatim excerpts):**

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.536 s -- in io.github.omerbar4.paymentledger.TransactionLedgerIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.229 s -- in io.github.omerbar4.paymentledger.DatabaseConstraintsIntegrationTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.286 s -- in io.github.omerbar4.paymentledger.IdempotencyIntegrationTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: /app/target/idempotent-payment-ledger-1.0.0.jar
[INFO] BUILD SUCCESS
[INFO] Total time:  8.877 s
[INFO] Finished at: 2026-09-30T04:10:50Z
```

**Results:**

| Metric | Result |
|---|---|
| Tests run | **27** |
| Failures / Errors / Skipped | **0 / 0 / 0** |
| Build | **BUILD SUCCESS** (exit code 0) |
| Artifact | `target/idempotent-payment-ledger-1.0.0.jar` (60,542,428 bytes, executable Spring Boot jar) |
| Test database | PostgreSQL 16.15 via Testcontainers 1.21.4 |
| Application ERROR/WARN log lines during the test run | 0 (excluding springdoc's informational "enabled by default" notices and the JDK's Mockito agent notice) |

The concurrency-focused test classes (`IdempotencyIntegrationTest`, `TransactionLedgerIntegrationTest`) were also run 3 additional times during development. All 21 of their tests passed on every run.

**Runtime smoke test.** The packaged jar was run against Docker Compose PostgreSQL and called with curl:

| Check | Observed |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` |
| First `POST /transactions` | `201`, `Idempotent-Replayed: false`, `POSTED`, DEBIT + CREDIT entries |
| Same key, same body | `200`, `Idempotent-Replayed: true` |
| Same key, different body | `422 IDEMPOTENCY_KEY_REUSED` |
| Recipient balance after payment | `"100.0000"` |
| `POST /transactions/{id}/refund` | `201`, `REFUND`, `POSTED`, reversing entries |
| Original payment after refund | `REFUNDED` |
| Recipient balance after refund | `"0.0000"` |
| `POST /transactions` without key | `400 MISSING_HEADER` |
| `/swagger-ui.html` · `/v3/api-docs` | `200` · `200` |

## 16. Project structure

```
.
├── FINAL_PROJECT_REPORT.md
├── README.md
├── compose.yaml                         # PostgreSQL 16 for local development
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
        └── DatabaseConstraintsIntegrationTest.java
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
- **Integration tests only.** All 27 tests are full-stack integration tests; there is no separate unit-test layer.
- **Package naming.** The Java package and Maven `groupId` are `io.github.omerbar4.paymentledger`.

## 18. Résumé evidence

| Claim | Evidence in code | Evidence in tests |
|---|---|---|
| DB-level, concurrency-safe idempotency | `uq_transactions_idempotency_key`; `IdempotentInsertRepositoryImpl` (`ON CONFLICT DO NOTHING`); `TransactionService.createPayment` / `refund` | `concurrentRequestsWithSameKeyCreateExactlyOneTransaction` (16 threads → 1 txn, 2 entries); `concurrentRefundsWithSameKeyCreateExactlyOneRefund` (12 threads → 1 refund) |
| Key-reuse detection | `RequestFingerprint` (SHA-256), `request_hash` column | `reusingKeyWithDifferentPayloadIsRejected`, `reusingPaymentKeyForRefundIsRejected` |
| Double-entry ledger invariant enforced by PostgreSQL | `trg_ledger_entries_balanced` (deferred), `trg_ledger_entries_append_only` | `unbalancedEntriesAreRejectedAtCommit`, `ledgerEntriesAreAppendOnly`, `ledgerIsGloballyBalancedAndBalancesMatchEntries` |
| No overdrafts under concurrency | `findAllByIdForUpdate` (ordered `FOR UPDATE`), `chk_accounts_non_negative_balance` | `concurrentPaymentsNeverOverdrawAnAccount` (20 threads → 10 posted / 10 failed, balance 0) |
| Exactly-once refunds | `findByIdForUpdate` on the original, partial unique index | `concurrentRefundsWithDifferentKeysRefundExactlyOnce` (10 threads → 1 × 201, 9 × 409) |
| Testcontainers integration suite | `TestcontainersConfiguration`, `AbstractIntegrationTest` | 27 / 27 passing against PostgreSQL 16.15 |

### Strongest résumé bullets

- **Built an idempotent payment ledger REST API in Java 21, Spring Boot 3.5 and PostgreSQL.** Idempotency is enforced at the database level with a unique constraint and `INSERT … ON CONFLICT DO NOTHING` in the same transaction as the ledger writes; verified that 16 concurrent requests with the same key produce exactly one transaction.
- **Enforced double-entry accounting invariants in PostgreSQL** with a deferred constraint trigger (debits = credits at commit), an append-only ledger trigger, and CHECK/partial-unique constraints. Pessimistic row locks taken in a fixed order prevent overdrafts and double refunds under concurrent load.
- **Wrote 27 JUnit 5 + Testcontainers integration tests** against real PostgreSQL covering duplicate and concurrent idempotency keys, race conditions (20 parallel payments never overdraw an account; 10 parallel refunds succeed exactly once), validation, RFC 9457 error handling, and database-level constraint enforcement.

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
| 24 | JUnit 5 + Testcontainers suite | 27 run, 0 failures, 0 errors, 0 skipped | **PASS** |

**Overall project status: COMPLETED**
