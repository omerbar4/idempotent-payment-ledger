# Measurement Report

Reproducible correctness, concurrency, latency and recovery evidence for the Idempotent Payment Ledger API. Each result below is labelled with who ran it, when, and against which code.

## 1. Scope and boundaries

- **What this is:** correctness and latency validation on one developer machine, with the API and PostgreSQL running in Docker containers.
- **What this is not:**
  - a production capacity benchmark, an SLA, or a statement about deployed behaviour (the project is not deployed)
  - evidence for multi-instance, load-balanced, multi-region or high-availability setups
  - evidence for real payment-provider integration (there is none)
  - evidence for sustained or soak load
- **Topology for every run:**
  - one API container (`eclipse-temurin:21-jre`)
  - one PostgreSQL 16 container (`postgres:16-alpine`) with a named volume
  - k6 in a third container, reaching the API through `host.docker.internal`
  - all three on the same host

## 2. Version and environment

| Item | Value | Source |
|---|---|---|
| Code under test | `2b460885415a65585a603d2cea984905a1e19065` (application, tests, scripts). The Maven run in §3 was on branch `chore/public-repo-readiness`, before any documentation edits, with only the new CI workflow file added | `git rev-parse HEAD`, `git status` |
| Host | macOS 26.4 (build 25E246), `arm64`, Apple M5, 10 cores, 16 GiB RAM | `sw_vers`, `uname -sm`, `sysctl` |
| Docker | Docker Desktop, engine and client 29.4.3; Compose v5.1.4 | `docker version`, `docker compose version` |
| Docker VM resources | 10 CPUs, 8,321,712,128 bytes (about 7.75 GiB) memory; LinuxKit kernel 6.12.76 | `docker info` |
| JDK | Eclipse Temurin 21.0.12.1 (`eclipse-temurin:21-jdk` image, linux/aarch64) | `./mvnw -v` inside the container |
| Maven | 3.9.11 via Maven Wrapper 3.3.4 | `./mvnw -v`, `.mvn/wrapper/maven-wrapper.properties` |
| Test database | `postgres:16-alpine` via Testcontainers 1.21.4 | `pom.xml` (managed by Spring Boot 3.5.16), test logs |
| k6 | `grafana/k6:1.8.1`, the default pinned in `scripts/load-test.sh` | script; the image was pulled and used in the prior-session runs |
| Where Java/Maven ran | **In a container.** The host has no JDK installed | §3 command |

## 3. Automated test evidence

**Claude-run, 2026-10-07 13:50:44–13:51:01 CDT (UTC−5).** Command, from the repository root:

```bash
docker run --rm -v "$PWD":/app -w /app -v ledger-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  eclipse-temurin:21-jdk sh -c './mvnw -v && ./mvnw -B clean verify'
```

With a local JDK 21 and Docker, the equivalent is `./mvnw -B clean verify`; the CI workflow runs exactly that.

Result:

```
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  15.843 s
```

| Test class | Tests | Origin |
|---|---|---|
| `IdempotencyIntegrationTest` | 9 | original core suite (27 tests) |
| `TransactionLedgerIntegrationTest` | 12 | original core suite |
| `DatabaseConstraintsIntegrationTest` | 6 | original core suite |
| `MetricsIntegrationTest` | 5 | added with the operational-validation work |
| `ApplicationRestartIntegrationTest` | 1 | added with the operational-validation work |

The 27/6 split comes from the per-class counts of this run. A separate run of the 27-test baseline was recorded in a prior session (before the operational-validation changes) and is listed in `FINAL_PROJECT_REPORT.md` §19.2; it was not re-run here.

Concurrency covered by the automated suite: 16 concurrent same-key payments → 1 transaction and 2 entries; 12 concurrent same-key refunds → 1 refund; 10 concurrent refunds with different keys → 1 success and 9 × `409`; 20 concurrent payments against a 100.00 balance → exactly 10 posted, 10 failed, final balance 0; a 10-thread same-key burst → metrics show +1 `posted` and +9 `replayed`.

## 4. Load-test methodology

**Rerun:**

```bash
docker compose up -d --build --wait     # API + PostgreSQL on 127.0.0.1
scripts/load-test.sh                    # k6 in Docker, then SQL ledger verification
```

| Parameter | Default | Variable |
|---|---|---|
| Virtual users (payments scenario) | 5, constant | `VUS` |
| Duration (payments scenario) | 30 s | `DURATION` |
| Think time per VU iteration | 0.1 s | `SLEEP_SECONDS` |
| Same-key burst size | 20 identical requests, one `Idempotency-Key`, sent in parallel with `http.batch` (`batchPerHost` = burst size), starting at t = 3 s | `BURST_SIZE` |
| Refund cycles | 5 sequential, starting at t = 1 s | `REFUND_ITERATIONS` |
| p95 sanity threshold (payments) | 1000 ms | `P95_THRESHOLD_MS` |
| Target | `http://localhost:8080` (rewritten to `host.docker.internal` for the k6 container) | `BASE_URL` |

The three scenarios run in parallel:

1. **`payments`:** unique-key payments from a funding account to 5 merchant accounts. Each response is checked for `201`, `Idempotent-Replayed: false`, status `POSTED`, and a DEBIT on the source plus a CREDIT on the destination for the exact requested amount. Every 10th payment is retried with the same key and must return a `200` replay with the same id.
2. **`idempotency_burst`:** the burst must produce exactly one `201` and `BURST_SIZE − 1` × `200` with `Idempotent-Replayed: true`, a single distinct transaction id, the same balanced posting in every response, and a stored transaction that is `POSTED` with 2 entries.
3. **`refunds`:** each cycle pays, refunds (`201`, `REFUND`, `POSTED`, reversing entries), retries the refund (`200`, same id), sends a second refund with a new key (`409 TRANSACTION_ALREADY_REFUNDED`), and checks that the payment is `REFUNDED`.

**Thresholds** (any failure makes k6 exit non-zero):
- `checks` rate == 100 %
- `http_req_failed` rate == 0; the only expected `409` is declared per request
- payments p95 < 1000 ms
- burst: created == 1, replayed == 19, other == 0, distinct ids == 1

**Database verification follows every load test.** `scripts/load-test.sh` runs `scripts/verify-ledger.sh <burst key>`, which queries PostgreSQL through `docker compose exec`:
- **For the burst key:** exactly 1 transaction, status `POSTED`, 2 entries, and Σ debit = Σ credit.
- **Globally:**
  - no duplicate idempotency keys
  - no transaction whose debits ≠ credits
  - every `POSTED`/`REFUNDED` transaction has exactly 2 entries
  - no `FAILED` transaction has entries
  - no transaction is stuck in `PENDING`
  - every account balance equals the sum of its entries
  - total debits equal total credits

The wrapper exits non-zero if either k6 or the verifier fails.

## 5. Observed results

### 5.1 User-run evidence (reported and supplied output)

Run independently by the repository owner. Values are recorded exactly as reported. The k6 lines below are quoted from the owner's local result file `load-test/results/summary.txt` (git-ignored, written 2026-10-07 13:29:32 CDT); the burst key is omitted.

```
Target: http://host.docker.internal:8080   VUS=5  DURATION=30s  SLEEP_SECONDS=0.1  BURST_SIZE=20  REFUND_ITERATIONS=5
HTTP requests   total=1522  rate=50.4/s
                succeeded=1522  failed(unexpected status)=0
Latency, all    avg=10.55ms  p50=8.23ms  p(90)=17.36ms  p(95)=22.03ms  p(99)=62.92ms  max=145.67ms
Latency, payments scenario  avg=10.42ms  p50=8.23ms  p(90)=16.84ms  p(95)=20.90ms  p(99)=60.92ms  max=145.67ms
Payments created (all checks passed): 1332
Payment retries replayed:             135
Refund cycles completed:              5 / 5
Idempotency burst: 20 concurrent identical requests, one key
                   201 created=1  200 replayed=19  other=0  distinct transaction ids=1  => PASS
Checks: 5768 passed, 0 failed
Overall: PASS   (all 7 thresholds PASS)
```

| Reported value | Recorded |
|---|---|
| HTTP requests | 1,522 |
| Unexpected failures | 0 |
| p95 latency (all requests) | 22.03 ms |
| Same-key burst | 1 creation, 19 replays |
| Database invariant verification | passed, as reported by the owner; that output is not in the result file |

Also reported by the owner for the same session: Compose stack healthy with `/actuator/health` showing database `UP`; Prometheus counters showing committed posted and replayed counts with zero failed counters; `scripts/recovery-check.sh` passed.

**Code traceability:** the result file predates commit `2b46088` (committed 13:37:19 CDT) by about 8 minutes. The commit under test isn't recorded in the output. In that interval the only changes made to the repository were the recovery-script project-name guard and one documentation table row. No application, test or load-test code changed.

### 5.2 Earlier Claude-run evidence (prior session, not re-run for this report)

This is recorded in `FINAL_PROJECT_REPORT.md` §19 and listed here only for comparison. It was run on the same machine, against an uncommitted working tree whose application and load-test code matches `2b46088`. The default configuration was 5 VUs, 30 s, 0.1 s think time, burst 20 and 5 refund cycles.

| Metric | Value |
|---|---|
| HTTP requests | 1,536; 0 unexpected statuses |
| Latency, all requests | p50 8.18 ms · p90 15.22 ms · p95 18.71 ms · p99 35.11 ms · max 100.10 ms |
| Burst | 1 created / 19 replayed / 1 distinct id |
| Checks | 5,824 passed, 0 failed |
| `verify-ledger.sh` | PASS |

No load test was run for this report: the evidence above already covers the methodology, and a rerun would only produce different latency numbers.

## 6. Restart / recovery methodology

**Rerun:** `scripts/recovery-check.sh`. Set `KEEP_STACK=1` to keep the containers for inspection.

**Isolation and safety:**
- The script uses its own Compose project, `ledger-recovery-check` (API on `127.0.0.1:18080`, PostgreSQL on `127.0.0.1:55433`), with its own network and named volume.
- It runs `docker compose -p ledger-recovery-check down -v` before starting and in its exit trap, so it removes only that project's containers, network and volume.
- A guard refuses (exit 2) any `RECOVERY_PROJECT` that doesn't start with `ledger-recovery-check`, so the cleanup can't target another project's volumes.
- No `docker system prune` or volume deletion by pattern is used.

| Step | Action | Assertions |
|---|---|---|
| 1 | Create accounts; `POST /transactions` with a known key | `201`, `POSTED`, `Idempotent-Replayed: false` |
| 2 | `verify-ledger.sh <key>` | 1 row for the key, 2 entries, debit = credit; global invariants |
| 3 | **API restart:** `docker compose up -d --force-recreate --no-deps --wait api` | the API container id changed, and the new container is healthy |
| 4 | Replay the identical request; `GET /transactions/{id}`; verify | `200`, `Idempotent-Replayed: true`, same id; `POSTED` with 2 entries; still 1 row and 2 entries |
| 5 | **PostgreSQL restart with retained data:** `docker compose up -d --force-recreate --no-deps --wait postgres` (same named volume) | the Postgres container id changed and is healthy; the **same** API container reports `db: UP` again within 120 s |
| 6 | Replay; new payment with a new key; account balance; verify | `200`, same id; `201 POSTED`; customer balance `76.5000` (75.50 applied once, plus 1.00); every invariant holds |

**Results:**
- **Owner-run:** passed, as reported on 2026-10-07.
- **Claude-run (prior session):** passed after the guard was added, with exit 0, 43 `PASS` lines, 0 `FAIL` lines and no leftover containers or volumes. In the same session, the guard was shown to refuse `RECOVERY_PROJECT=idempotent-payment-ledger` with exit 2 and existing volumes intact.
- **Boundary:** containers were stopped with Docker's normal stop. A hard kill, crash or a request interrupted mid-transaction was not tested.

The automated counterpart is `ApplicationRestartIntegrationTest`, which ran in §3. It shuts down one application instance and starts a new one against the same database. The replay then returns the same id with 1 row and 2 entries.

## 7. How to interpret p95

- The p95 figures (22.03 ms in the owner's run, 18.71 ms in the earlier Claude run) describe **this** environment and **this** traffic pattern only:
  - one Apple-silicon laptop, with containers in a Docker Desktop VM
  - about 50 requests/s from 5 VUs with 0.1 s think time
  - a mix of small payments, replays and refund cycles
  - a local database whose size at run time wasn't recorded (the main volume can hold rows from earlier runs)
  - no network distance between the client and the API
- They show that the locking and idempotency design adds no pathological latency at this modest local concurrency, and that the 20-request same-key burst completes without errors.
- They are **not** a prediction of production latency or throughput. Different hardware, network hops, connection-pool sizing, database size, hot accounts, multiple API instances or sustained load would all change them. Two runs of the same command on the same machine already differ (18.71 ms vs 22.03 ms).
- The 1000 ms threshold in the script is a sanity guard against gross regressions, not an SLO.

## 8. Reproduce everything

```bash
./mvnw -B clean verify                   # or the containerised command in §3
docker compose up -d --build --wait
scripts/load-test.sh                     # k6 + SQL verification; writes load-test/results/ (git-ignored)
scripts/recovery-check.sh                # isolated project; cleans up after itself
docker compose down                      # add -v only if you want to delete this project's data
```
