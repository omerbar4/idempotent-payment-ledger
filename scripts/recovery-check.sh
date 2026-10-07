#!/usr/bin/env bash
# Restart / durability validation using Docker Compose.
#
# Runs in its OWN Compose project (default: ledger-recovery-check) with its own network, volume and
# ports, so it never touches the main stack or any other containers. On exit it removes only that
# project's containers, network and volume (set KEEP_STACK=1 to keep them for inspection).
#
# Sequence:
#   1. start postgres + api, create accounts, POST a payment with a known Idempotency-Key
#   2. verify the response and the persisted rows/entries in PostgreSQL
#   3. recreate the API container (new process, same database volume)
#   4. replay the identical request -> 200 replay of the same transaction, no new rows/entries
#   5. recreate the PostgreSQL container (new container, same named volume) while the API keeps running
#   6. wait for the API to reconnect, replay again, prove writes work, re-verify all ledger invariants
#
# Environment: RECOVERY_PROJECT (must start with "ledger-recovery-check"), RECOVERY_API_PORT (18080),
# RECOVERY_POSTGRES_PORT (55433), KEEP_STACK.
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT="${RECOVERY_PROJECT:-ledger-recovery-check}"
# This script runs `down -v` on $PROJECT, so it must never point at another project's data.
if [[ "$PROJECT" != ledger-recovery-check* ]]; then
  echo "recovery-check: RECOVERY_PROJECT must start with 'ledger-recovery-check' (got '$PROJECT');" \
       "refusing to run because this project's volumes are deleted on exit." >&2
  exit 2
fi
export API_PORT="${RECOVERY_API_PORT:-18080}"
export POSTGRES_PORT="${RECOVERY_POSTGRES_PORT:-55433}"
BASE="http://127.0.0.1:${API_PORT}"
TMP="$(mktemp -d)"
STEP="init"

compose() { docker compose -p "$PROJECT" "$@"; }

log()  { printf '\n==> %s\n' "$*"; STEP="$*"; }
pass() { printf '  PASS  %s\n' "$*"; }

diagnostics() {
  echo "---- diagnostics (compose project: $PROJECT) ----" >&2
  compose ps -a >&2 || true
  compose logs --no-color --tail 40 api >&2 || true
  compose logs --no-color --tail 20 postgres >&2 || true
}

fail() {
  printf '  FAIL  %s\n' "$*" >&2
  printf 'Recovery check FAILED during step: %s\n' "$STEP" >&2
  diagnostics
  exit 1
}

cleanup() {
  local status=$?
  rm -rf "$TMP"
  if [[ "${KEEP_STACK:-0}" == "1" ]]; then
    echo "KEEP_STACK=1: leaving compose project '$PROJECT' running (remove with: docker compose -p $PROJECT down -v)"
  else
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  if [[ $status -ne 0 ]]; then
    echo "Recovery check exited with status $status (last step: $STEP)" >&2
  fi
}
trap cleanup EXIT

# http METHOD PATH [IDEMPOTENCY_KEY] [JSON_BODY] -> prints status; body in $TMP/body, headers in $TMP/headers
http() {
  local args=(-sS -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$1" "$BASE$2"
              -H 'Content-Type: application/json')
  [[ -n "${3:-}" ]] && args+=(-H "Idempotency-Key: $3")
  [[ -n "${4:-}" ]] && args+=(-d "$4")
  curl "${args[@]}" || true   # on connection errors curl prints 000 via -w
}
# First occurrence of a string field in the compact JSON body (top-level fields come first).
field()  { grep -o "\"$1\":\"[^\"]*\"" "$TMP/body" | head -1 | sed -E 's/^"[^"]*":"(.*)"$/\1/'; }
header() { tr -d '\r' < "$TMP/headers" | grep -i "^$1:" | head -1 | sed -E 's/^[^:]*: *//'; }
container_id() { compose ps -q "$1"; }

wait_ready() { # wait until readiness is UP and the db health component is UP
  local deadline=$((SECONDS + ${1:-120}))
  while (( SECONDS < deadline )); do
    if curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"db":{"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

verify_db() {
  COMPOSE_PROJECT="$PROJECT" scripts/verify-ledger.sh "$KEY" || fail "ledger verification in PostgreSQL"
}

log "Preparing isolated compose project '$PROJECT' (api :$API_PORT, postgres :$POSTGRES_PORT)"
compose down -v --remove-orphans >/dev/null 2>&1 || true   # leftovers of a previous run of THIS project only
compose up -d --build --wait --wait-timeout 300 >"$TMP/up.log" 2>&1 \
  || { cat "$TMP/up.log" >&2; fail "docker compose up"; }
pass "postgres and api containers are healthy"
API_CONTAINER_1="$(container_id api)"
PG_CONTAINER_1="$(container_id postgres)"

log "Step 1: create accounts and a payment with a known Idempotency-Key"
[[ "$(http POST /accounts '' '{"name":"recovery-funding","currency":"USD","allowNegativeBalance":true}')" == 201 ]] \
  || fail "create funding account: $(cat "$TMP/body")"
FUNDING="$(field id)"
[[ "$(http POST /accounts '' '{"name":"recovery-customer","currency":"USD"}')" == 201 ]] \
  || fail "create customer account: $(cat "$TMP/body")"
CUSTOMER="$(field id)"
KEY="recovery-$(date +%s)-$RANDOM"
BODY="{\"sourceAccountId\":\"$FUNDING\",\"destinationAccountId\":\"$CUSTOMER\",\"amount\":\"75.50\",\"currency\":\"USD\",\"description\":\"recovery check\"}"

status="$(http POST /transactions "$KEY" "$BODY")"
[[ "$status" == 201 ]] || fail "first POST expected 201, got $status: $(cat "$TMP/body")"
TX_ID="$(field id)"
[[ "$(field status)" == POSTED ]] || fail "first POST expected status POSTED: $(cat "$TMP/body")"
[[ "$(header Idempotent-Replayed)" == false ]] || fail "first POST expected Idempotent-Replayed: false"
pass "POST /transactions -> 201, status POSTED, Idempotent-Replayed: false (transaction $TX_ID)"

log "Step 2: confirm persisted state in PostgreSQL"
verify_db

log "Step 3: recreate the API container (new process, same database)"
compose up -d --force-recreate --no-deps --wait --wait-timeout 180 api >"$TMP/up.log" 2>&1 \
  || { cat "$TMP/up.log" >&2; fail "recreate api"; }
API_CONTAINER_2="$(container_id api)"
[[ -n "$API_CONTAINER_2" && "$API_CONTAINER_2" != "$API_CONTAINER_1" ]] || fail "api container was not replaced"
pass "api container replaced (${API_CONTAINER_1:0:12} -> ${API_CONTAINER_2:0:12}) and healthy"

log "Step 4: replay the identical request after the API restart"
status="$(http POST /transactions "$KEY" "$BODY")"
[[ "$status" == 200 ]] || fail "replay expected 200, got $status: $(cat "$TMP/body")"
[[ "$(field id)" == "$TX_ID" ]] || fail "replay returned a different transaction id: $(field id)"
[[ "$(header Idempotent-Replayed)" == true ]] || fail "replay expected Idempotent-Replayed: true"
pass "POST with the same key -> 200, Idempotent-Replayed: true, same transaction id"
[[ "$(http GET "/transactions/$TX_ID")" == 200 ]] || fail "GET /transactions/$TX_ID"
[[ "$(field status)" == POSTED && "$(grep -o '"direction"' "$TMP/body" | wc -l | tr -d ' ')" == 2 ]] \
  || fail "GET expected POSTED with 2 entries: $(cat "$TMP/body")"
pass "GET /transactions/{id} -> POSTED with 2 ledger entries"
verify_db

log "Step 5: recreate the PostgreSQL container (same named volume) while the API keeps running"
compose up -d --force-recreate --no-deps --wait --wait-timeout 120 postgres >"$TMP/up.log" 2>&1 \
  || { cat "$TMP/up.log" >&2; fail "recreate postgres"; }
PG_CONTAINER_2="$(container_id postgres)"
[[ -n "$PG_CONTAINER_2" && "$PG_CONTAINER_2" != "$PG_CONTAINER_1" ]] || fail "postgres container was not replaced"
pass "postgres container replaced (${PG_CONTAINER_1:0:12} -> ${PG_CONTAINER_2:0:12}) and healthy"
wait_ready 120 || fail "API did not report db UP within 120s after the PostgreSQL restart"
[[ "$(container_id api)" == "$API_CONTAINER_2" ]] || fail "api container changed unexpectedly"
pass "API reconnected to the new PostgreSQL container without being restarted"

log "Step 6: replay again, prove new writes work, re-verify invariants"
status="$(http POST /transactions "$KEY" "$BODY")"
[[ "$status" == 200 && "$(field id)" == "$TX_ID" ]] \
  || fail "replay after PostgreSQL restart expected 200 with the same id, got $status: $(cat "$TMP/body")"
pass "POST with the same key -> 200 replay of the same transaction"
status="$(http POST /transactions "$KEY-new" "${BODY/75.50/1.00}")"
[[ "$status" == 201 && "$(field status)" == POSTED ]] || fail "new payment after restart expected 201 POSTED, got $status"
pass "new payment with a new key -> 201 POSTED (writes work after the restart)"
[[ "$(http GET "/accounts/$CUSTOMER")" == 200 && "$(field balance)" == "76.5000" ]] \
  || fail "customer balance expected 76.5000: $(cat "$TMP/body")"
pass "customer balance is 76.5000 (75.50 once, not twice, plus 1.00)"
verify_db

log "Recovery check PASSED"
