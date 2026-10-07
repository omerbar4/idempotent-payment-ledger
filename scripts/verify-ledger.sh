#!/usr/bin/env bash
# Verifies ledger invariants directly in PostgreSQL (via `docker compose exec`, no local psql needed).
#
#   scripts/verify-ledger.sh [IDEMPOTENCY_KEY]
#
# With a key: asserts that exactly one transaction exists for it, that it is POSTED and that it has
# exactly one DEBIT and one CREDIT of equal amount. Always: asserts global ledger invariants.
#
# COMPOSE_PROJECT selects the Compose project (default: idempotent-payment-ledger).
# Exit code 0 = all checks passed, 1 = at least one failed, 2 = could not query the database.
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT="${COMPOSE_PROJECT:-idempotent-payment-ledger}"
KEY="${1:-}"
failures=0

sql() {
  # Values are passed as psql variables (:'key'), never interpolated into SQL text.
  docker compose -p "$PROJECT" exec -T postgres \
    psql -U ledger -d ledger -X -q -A -t -v ON_ERROR_STOP=1 -v key="$KEY"
}

expect() { # expect <description> <expected> <actual>
  if [[ "$3" == "$2" ]]; then
    printf '  PASS  %-62s %s\n' "$1" "$3"
  else
    printf '  FAIL  %-62s expected %s, got %s\n' "$1" "$2" "$3"
    failures=$((failures + 1))
  fi
}

if ! docker compose -p "$PROJECT" exec -T postgres pg_isready -U ledger -d ledger >/dev/null 2>&1; then
  echo "verify-ledger: PostgreSQL of compose project '$PROJECT' is not running/ready." >&2
  docker compose -p "$PROJECT" ps >&2 || true
  exit 2
fi

echo "Ledger verification (compose project: $PROJECT)"

if [[ -n "$KEY" ]]; then
  # One query, '|'-separated: rows for key | status | entry count | debit sum | credit sum
  IFS='|' read -r rows status entries debits credits < <(sql <<'SQL'
SELECT (SELECT count(*) FROM transactions WHERE idempotency_key = :'key'),
       coalesce(max(t.status), '-'),
       count(e.id),
       coalesce(sum(e.amount) FILTER (WHERE e.direction = 'DEBIT'), 0),
       coalesce(sum(e.amount) FILTER (WHERE e.direction = 'CREDIT'), 0)
  FROM transactions t LEFT JOIN ledger_entries e ON e.transaction_id = t.id
 WHERE t.idempotency_key = :'key';
SQL
)
  echo "Key-specific checks (key length ${#KEY}, value not printed):"
  expect "transactions stored for this Idempotency-Key" 1 "$rows"
  expect "transaction status" POSTED "$status"
  expect "ledger entries for the transaction" 2 "$entries"
  expect "debits equal credits for the transaction" "$debits" "$credits"
fi

IFS='|' read -r dup_keys unbalanced bad_posted bad_failed pending bad_balances debits credits total_tx total_entries < <(sql <<'SQL'
SELECT
  (SELECT count(*) FROM (SELECT idempotency_key FROM transactions GROUP BY 1 HAVING count(*) > 1) d),
  (SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY 1
      HAVING sum(amount) FILTER (WHERE direction = 'DEBIT') IS DISTINCT FROM
             sum(amount) FILTER (WHERE direction = 'CREDIT')) u),
  (SELECT count(*) FROM transactions t WHERE t.status IN ('POSTED', 'REFUNDED')
      AND (SELECT count(*) FROM ledger_entries e WHERE e.transaction_id = t.id) <> 2),
  (SELECT count(*) FROM transactions t WHERE t.status = 'FAILED'
      AND EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transaction_id = t.id)),
  (SELECT count(*) FROM transactions WHERE status = 'PENDING'),
  (SELECT count(*) FROM accounts a WHERE a.balance <> coalesce((SELECT sum(CASE e.direction
      WHEN 'CREDIT' THEN e.amount ELSE -e.amount END) FROM ledger_entries e WHERE e.account_id = a.id), 0)),
  (SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE direction = 'DEBIT'),
  (SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE direction = 'CREDIT'),
  (SELECT count(*) FROM transactions),
  (SELECT count(*) FROM ledger_entries);
SQL
)
echo "Global checks ($total_tx transactions, $total_entries ledger entries):"
expect "idempotency keys used by more than one transaction" 0 "$dup_keys"
expect "transactions whose debits != credits" 0 "$unbalanced"
expect "POSTED/REFUNDED transactions without exactly 2 entries" 0 "$bad_posted"
expect "FAILED transactions that have ledger entries" 0 "$bad_failed"
expect "transactions stuck in PENDING" 0 "$pending"
expect "accounts whose balance != sum of their entries" 0 "$bad_balances"
expect "total debits equal total credits" "$debits" "$credits"

if (( failures > 0 )); then
  echo "Ledger verification: FAIL ($failures check(s) failed)"
  exit 1
fi
echo "Ledger verification: PASS"
