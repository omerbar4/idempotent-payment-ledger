#!/usr/bin/env bash
# Runs the k6 load test (in Docker, pinned image) against a running API, then verifies the
# idempotency burst and the global ledger invariants directly in PostgreSQL.
#
# Prerequisite: the stack is up, e.g. `docker compose up -d --build --wait`.
#
# Environment: BASE_URL (default http://localhost:${API_PORT:-8080}), VUS, DURATION, SLEEP_SECONDS, BURST_SIZE,
# REFUND_ITERATIONS, P95_THRESHOLD_MS (see load-test/ledger-load.js), K6_IMAGE, COMPOSE_PROJECT.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:${API_PORT:-8080}}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.8.1}"
RESULTS=load-test/results

if ! curl -fsS "$BASE_URL/actuator/health" >/dev/null; then
  echo "load-test: API is not reachable/healthy at $BASE_URL" >&2
  echo "Start it with: docker compose up -d --build --wait" >&2
  exit 2
fi

# Inside the k6 container, the host is host.docker.internal.
K6_BASE_URL="$(printf '%s' "$BASE_URL" | sed -E 's#//(localhost|127\.0\.0\.1)#//host.docker.internal#')"

mkdir -p "$RESULTS"
rm -f "$RESULTS/burst.env" "$RESULTS/summary.json" "$RESULTS/summary.txt"

set +e
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --add-host host.docker.internal:host-gateway \
  -v "$PWD/load-test:/load-test" \
  -e BASE_URL="$K6_BASE_URL" \
  -e VUS -e DURATION -e SLEEP_SECONDS -e BURST_SIZE -e REFUND_ITERATIONS -e P95_THRESHOLD_MS \
  -e RESULTS_DIR=/load-test/results \
  "$K6_IMAGE" run --quiet /load-test/ledger-load.js
k6_status=$?
set -e

if [[ ! -f "$RESULTS/burst.env" ]]; then
  echo "load-test: k6 did not produce $RESULTS/burst.env (exit $k6_status)" >&2
  exit 1
fi
# shellcheck disable=SC1091
source "$RESULTS/burst.env"

echo
set +e
scripts/verify-ledger.sh "$BURST_KEY"
verify_status=$?
set -e

echo
echo "k6 exit code: $k6_status (0 = all checks and thresholds passed)"
echo "ledger verification exit code: $verify_status"
echo "Results: $RESULTS/summary.txt, $RESULTS/summary.json"
[[ $k6_status -eq 0 && $verify_status -eq 0 ]]
