// Local load / correctness validation for the Idempotent Payment Ledger API.
// This is a deterministic demo of behaviour under concurrency, NOT a capacity benchmark.
//
// Scenarios (run in parallel):
//   payments           VUS virtual users create payments with unique Idempotency-Keys for DURATION;
//                      every 10th payment is immediately retried with the same key (must replay).
//   idempotency_burst  BURST_SIZE identical requests with ONE Idempotency-Key sent concurrently
//                      (http.batch); exactly one may create the transaction, the rest must replay it.
//   refunds            REFUND_ITERATIONS sequential cycles: pay -> refund -> replay refund ->
//                      second refund with a new key (must be 409) -> payment is REFUNDED.
//
// Environment variables (all optional):
//   BASE_URL           default http://localhost:8080
//   VUS                default 5
//   DURATION           default 30s
//   BURST_SIZE         default 20
//   REFUND_ITERATIONS  default 5
//   SLEEP_SECONDS      default 0.1   (pause between payments per VU; keeps the default load modest)
//   P95_THRESHOLD_MS   default 1000  (sanity guard for the payments scenario, not an SLO)
//   RESULTS_DIR        default load-test/results

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Gauge } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const VUS = parseInt(__ENV.VUS || '5', 10);
const DURATION = __ENV.DURATION || '30s';
const BURST_SIZE = parseInt(__ENV.BURST_SIZE || '20', 10);
const REFUND_ITERATIONS = parseInt(__ENV.REFUND_ITERATIONS || '5', 10);
const P95_THRESHOLD_MS = parseInt(__ENV.P95_THRESHOLD_MS || '1000', 10);
const SLEEP_SECONDS = parseFloat(__ENV.SLEEP_SECONDS || '0.1');
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load-test/results';
const MERCHANTS = 5;

const paymentsCreated = new Counter('payments_created');
const paymentRetriesReplayed = new Counter('payment_retries_replayed');
const burstCreated = new Counter('burst_created_responses');
const burstReplayed = new Counter('burst_replayed_responses');
const burstOther = new Counter('burst_other_responses');
const burstDistinctIds = new Gauge('burst_distinct_transaction_ids');
const refundCyclesCompleted = new Counter('refund_cycles_completed');

export const options = {
  scenarios: {
    payments: { executor: 'constant-vus', exec: 'payments', vus: VUS, duration: DURATION },
    idempotency_burst: {
      executor: 'per-vu-iterations', exec: 'burst', vus: 1, iterations: 1, startTime: '3s', maxDuration: '60s',
    },
    refunds: {
      executor: 'per-vu-iterations', exec: 'refunds', vus: 1, iterations: REFUND_ITERATIONS,
      startTime: '1s', maxDuration: '120s',
    },
  },
  // Let http.batch open BURST_SIZE parallel connections so the burst is truly concurrent.
  batch: BURST_SIZE,
  batchPerHost: BURST_SIZE,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1.0'],
    http_req_failed: ['rate==0'],
    'http_req_duration{scenario:payments}': [`p(95)<${P95_THRESHOLD_MS}`],
    burst_created_responses: ['count==1'],
    burst_replayed_responses: [`count==${BURST_SIZE - 1}`],
    burst_other_responses: ['count==0'],
    burst_distinct_transaction_ids: ['value==1'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(path, body, key, extra = {}) {
  const headers = Object.assign({}, JSON_HEADERS, key ? { 'Idempotency-Key': key } : {});
  return http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), Object.assign({ headers }, extra));
}

function createAccount(name, allowNegativeBalance) {
  const res = post('/accounts', { name, currency: 'USD', allowNegativeBalance });
  if (res.status !== 201) fail(`setup: could not create account ${name}: HTTP ${res.status} ${res.body}`);
  return res.json('id');
}

function entry(tx, direction) {
  return (tx.entries || []).find((e) => e.direction === direction) || {};
}

// "12.34" -> "12.3400" (the API returns amounts at ledger scale 4)
function atLedgerScale(amount) {
  return `${amount}00`;
}

function isBalancedPosting(tx, from, to, amount) {
  return tx.entries && tx.entries.length === 2
    && entry(tx, 'DEBIT').accountId === from && entry(tx, 'CREDIT').accountId === to
    && entry(tx, 'DEBIT').amount === atLedgerScale(amount) && entry(tx, 'CREDIT').amount === atLedgerScale(amount);
}

export function setup() {
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200 || health.json('status') !== 'UP') {
    fail(`API not healthy at ${BASE_URL}: HTTP ${health.status} ${health.body}`);
  }
  const runId = `lt-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
  const merchants = [];
  for (let i = 0; i < MERCHANTS; i++) merchants.push(createAccount(`${runId}-merchant-${i}`, false));
  return {
    runId,
    funding: createAccount(`${runId}-funding`, true),
    merchants,
    burstDestination: createAccount(`${runId}-burst-merchant`, false),
    refundMerchant: createAccount(`${runId}-refund-merchant`, false),
    burstKey: `${runId}-burst`,
  };
}

export function payments(data) {
  const key = `${data.runId}-pay-${__VU}-${__ITER}`;
  const to = data.merchants[(__VU + __ITER) % data.merchants.length];
  const amount = (1 + Math.floor(Math.random() * 4900) / 100).toFixed(2); // 1.00 .. 49.99
  const body = { sourceAccountId: data.funding, destinationAccountId: to, amount, currency: 'USD' };

  const res = post('/transactions', body, key);
  const tx = res.status === 201 ? res.json() : {};
  const ok = check(res, {
    'payment: 201 Created': (r) => r.status === 201,
    'payment: not a replay': (r) => r.headers['Idempotent-Replayed'] === 'false',
    'payment: status POSTED': () => tx.status === 'POSTED',
    'payment: balanced DEBIT/CREDIT pair for the requested amount': () => isBalancedPosting(tx, data.funding, to, amount),
  });
  if (ok) paymentsCreated.add(1);

  if (__ITER % 10 === 0) {
    const retry = post('/transactions', body, key);
    const replayOk = check(retry, {
      'payment retry: 200 OK': (r) => r.status === 200,
      'payment retry: Idempotent-Replayed true': (r) => r.headers['Idempotent-Replayed'] === 'true',
      'payment retry: same transaction id': (r) => r.status === 200 && r.json('id') === tx.id,
    });
    if (replayOk) paymentRetriesReplayed.add(1);
  }
  sleep(SLEEP_SECONDS);
}

export function burst(data) {
  const body = JSON.stringify({
    sourceAccountId: data.funding, destinationAccountId: data.burstDestination,
    amount: '25.00', currency: 'USD', description: 'idempotency burst',
  });
  const params = { headers: Object.assign({ 'Idempotency-Key': data.burstKey }, JSON_HEADERS) };
  const requests = [];
  for (let i = 0; i < BURST_SIZE; i++) requests.push(['POST', `${BASE_URL}/transactions`, body, params]);

  const responses = http.batch(requests);
  const ids = new Set();
  let created = 0;
  let replayed = 0;
  let other = 0;
  let allBalanced = true;
  for (const r of responses) {
    if (r.status === 201) created++;
    else if (r.status === 200 && r.headers['Idempotent-Replayed'] === 'true') replayed++;
    else other++;
    if (r.status === 200 || r.status === 201) {
      const tx = r.json();
      ids.add(tx.id);
      allBalanced = allBalanced && isBalancedPosting(tx, data.funding, data.burstDestination, '25.00');
    }
  }
  burstCreated.add(created);
  burstReplayed.add(replayed);
  burstOther.add(other);
  burstDistinctIds.add(ids.size);

  check(null, {
    'burst: exactly one 201 Created': () => created === 1,
    'burst: all other responses are 200 replays': () => replayed === BURST_SIZE - 1,
    'burst: every response carries the same transaction id': () => ids.size === 1,
    'burst: every response shows the same balanced posting': () => allBalanced,
  });

  const [id] = [...ids];
  const fetched = http.get(`${BASE_URL}/transactions/${id}`);
  check(fetched, {
    'burst: stored transaction is POSTED with exactly 2 entries': (r) =>
      r.status === 200 && r.json('status') === 'POSTED' && r.json('entries').length === 2,
  });
}

export function refunds(data) {
  const amount = '10.00';
  const pay = post('/transactions', {
    sourceAccountId: data.funding, destinationAccountId: data.refundMerchant, amount, currency: 'USD',
  }, `${data.runId}-refund-pay-${__ITER}`);
  const paymentId = pay.status === 201 ? pay.json('id') : null;
  if (!check(pay, { 'refund cycle: payment 201 POSTED': (r) => r.status === 201 && r.json('status') === 'POSTED' })) {
    return;
  }

  const refundKey = `${data.runId}-refund-${__ITER}`;
  const refund = post(`/transactions/${paymentId}/refund`, null, refundKey);
  const refundTx = refund.status === 201 ? refund.json() : {};
  const retry = post(`/transactions/${paymentId}/refund`, null, refundKey);
  const second = post(`/transactions/${paymentId}/refund`, null, `${refundKey}-second`,
    { responseCallback: http.expectedStatuses(409) });
  const original = http.get(`${BASE_URL}/transactions/${paymentId}`);

  const ok = check(null, {
    'refund: 201 REFUND POSTED linked to the payment': () =>
      refund.status === 201 && refundTx.type === 'REFUND' && refundTx.status === 'POSTED'
      && refundTx.refundOfId === paymentId,
    'refund: reversing entries (DEBIT merchant, CREDIT funding)': () =>
      isBalancedPosting(refundTx, data.refundMerchant, data.funding, amount),
    'refund retry: 200 replay of the same refund': () =>
      retry.status === 200 && retry.headers['Idempotent-Replayed'] === 'true' && retry.json('id') === refundTx.id,
    'refund with new key: 409 TRANSACTION_ALREADY_REFUNDED': () =>
      second.status === 409 && second.json('code') === 'TRANSACTION_ALREADY_REFUNDED',
    'refund: original payment is REFUNDED': () => original.status === 200 && original.json('status') === 'REFUNDED',
  });
  if (ok) refundCyclesCompleted.add(1);
}

function metric(data, name, stat, digits = 2) {
  const m = data.metrics[name];
  if (!m || m.values[stat] === undefined) return 'n/a';
  return Number(m.values[stat]).toFixed(digits);
}

function count(data, name) {
  const m = data.metrics[name];
  return m ? (m.values.count !== undefined ? m.values.count : m.values.value) : 0;
}

function latencyLine(data, name) {
  return ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
    .map((s) => `${s === 'med' ? 'p50' : s}=${metric(data, name, s)}ms`).join('  ');
}

export function handleSummary(data) {
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values;
  const checks = data.metrics.checks.values;
  const thresholdLines = [];
  let allThresholdsOk = true;
  for (const [name, m] of Object.entries(data.metrics)) {
    for (const [expr, t] of Object.entries(m.thresholds || {})) {
      allThresholdsOk = allThresholdsOk && t.ok;
      thresholdLines.push(`  ${t.ok ? 'PASS' : 'FAIL'}  ${name}: ${expr}`);
    }
  }
  const created = count(data, 'burst_created_responses');
  const replayed = count(data, 'burst_replayed_responses');
  const other = count(data, 'burst_other_responses');
  const distinct = count(data, 'burst_distinct_transaction_ids');
  const burstOk = created === 1 && replayed === BURST_SIZE - 1 && other === 0 && distinct === 1;
  const setupData = data.setup_data || {};

  const text = [
    '',
    '================ Ledger load test summary ================',
    '(local correctness/latency validation; not a capacity benchmark)',
    `Target: ${BASE_URL}   VUS=${VUS}  DURATION=${DURATION}  SLEEP_SECONDS=${SLEEP_SECONDS}  BURST_SIZE=${BURST_SIZE}  REFUND_ITERATIONS=${REFUND_ITERATIONS}`,
    '',
    `HTTP requests   total=${reqs.count}  rate=${Number(reqs.rate).toFixed(1)}/s`,
    `                succeeded=${failed.fails}  failed(unexpected status)=${failed.passes}`,
    `Latency, all    ${latencyLine(data, 'http_req_duration')}`,
    `Latency, payments scenario  ${latencyLine(data, 'http_req_duration{scenario:payments}')}`,
    '',
    `Payments created (all checks passed): ${count(data, 'payments_created')}`,
    `Payment retries replayed:             ${count(data, 'payment_retries_replayed')}`,
    `Refund cycles completed:              ${count(data, 'refund_cycles_completed')} / ${REFUND_ITERATIONS}`,
    `Idempotency burst: ${BURST_SIZE} concurrent identical requests, one key`,
    `                   201 created=${created}  200 replayed=${replayed}  other=${other}  distinct transaction ids=${distinct}  => ${burstOk ? 'PASS' : 'FAIL'}`,
    `Checks: ${checks.passes} passed, ${checks.fails} failed`,
    'Thresholds:',
    ...thresholdLines,
    `Overall: ${allThresholdsOk ? 'PASS' : 'FAIL'}`,
    `Burst Idempotency-Key (for DB verification): ${setupData.burstKey}`,
    '==========================================================',
    '',
  ].join('\n');

  return {
    stdout: text,
    [`${RESULTS_DIR}/summary.json`]: JSON.stringify(data, null, 2),
    [`${RESULTS_DIR}/summary.txt`]: text,
    [`${RESULTS_DIR}/burst.env`]: `BURST_KEY=${setupData.burstKey}\nRUN_ID=${setupData.runId}\n`,
  };
}
