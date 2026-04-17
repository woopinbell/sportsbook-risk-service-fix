// Repeatable risk check gate.
//
// The runner owns the lifecycle and invokes this file in one of two phases:
//   - warmup: 1,000 RPS for 60 seconds, excluded from measured evidence
//   - measure: 1,000 RPS for 60 seconds with the repository's strict latency gate

import http from 'k6/http';
import { check } from 'k6';

const isWarmup = __ENV.PHASE === 'warmup';

export const options = {
  scenarios: {
    risk_check_1000rps: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 200,
      maxVUs: 500,
      gracefulStop: '5s',
    },
  },
  thresholds: isWarmup
    ? {}
    : {
        http_req_duration: ['p(50)<5', 'p(95)<15', 'p(99)<30'],
        http_req_failed: ['rate<0.001'],
        checks: ['rate>0.999'],
        dropped_iterations: ['count==0'],
      },
  summaryTrendStats: ['min', 'avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const baseUrl = __ENV.RISK_BASE_URL || 'http://localhost:8083';
const selectionIds = [
  '10000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000003',
  '10000000-0000-4000-8000-000000000004',
  '10000000-0000-4000-8000-000000000005',
];

function uuid(prefix, ordinal) {
  const suffix = String(ordinal % 1000000000000).padStart(12, '0');
  return `${prefix}0000000-0000-4000-8000-${suffix}`;
}

export default function () {
  const userOrdinal = ((__VU * 997) + (__ITER % 1000)) % 1000000;
  const betOrdinal = (__VU * 1000000) + __ITER;
  const body = JSON.stringify({
    userId: uuid('2', userOrdinal),
    betId: uuid('3', betOrdinal),
    stake: { amount: 10000, currency: 'KRW' },
    selectionIds: [selectionIds[__ITER % selectionIds.length]],
  });

  const response = http.post(`${baseUrl}/internal/v1/risk/check`, body, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'risk_check_gate' },
  });

  check(response, {
    'response is HTTP 200 with an approved decision': (result) =>
      result.status === 200 && result.json('approved') === true,
  });
}
