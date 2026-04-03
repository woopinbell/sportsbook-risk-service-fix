// risk-service /internal/v1/risk/check critical-path latency baseline.
//
// Two scenarios run back-to-back so a single k6 invocation captures both numbers the
// retrospective needs:
//
//   1. `realistic_1000rps` — constant-arrival-rate at 1 000 requests/second for 60s
//      against the sportsbook target (p99 < 30 ms at the betting-service critical path).
//      k6 sizes the VU pool against the arrival rate so latency reflects service work,
//      not client-side queueing.
//
//   2. `saturation_5000vu` — constant-vus 5 000 for 30s. This isn't realistic per-user
//      load; it shows where the service queue-saturates so the README can quote the
//      p99 cliff alongside the realistic target.
//
// Run:
//   k6 run --summary-export results/$(date +%Y-%m-%d)/check_latency.json scenarios/check_latency.js
//
// Required environment:
//   - risk-service running on http://localhost:8083 (./mvnw spring-boot:run from repo root)
//   - load-test/docker-compose.yml up so Redis (6390) and Kafka (9094) are available
//   - SERVER_PORT=8083 REDIS_PORT=6390 KAFKA_BOOTSTRAP=localhost:9094 set on the spring-boot
//     invocation so the dev application.yml defaults are overridden.

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    realistic_1000rps: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 200,
      maxVUs: 500,
      gracefulStop: '5s',
    },
    saturation_5000vu: {
      executor: 'constant-vus',
      vus: 5000,
      duration: '30s',
      startTime: '70s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    // Realistic-load contract — this is the target the README quotes.
    'http_req_duration{scenario:realistic_1000rps,expected_response:true}': [
      'p(99)<30',
      'p(95)<15',
      'p(50)<5',
    ],
    'http_req_failed{scenario:realistic_1000rps}': ['rate<0.001'],
    // Saturation has no SLO; assert only that no error rate spikes.
    'http_req_failed{scenario:saturation_5000vu}': ['rate<0.01'],
    checks: ['rate>0.999'],
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const BASE = __ENV.RISK_BASE_URL || 'http://localhost:8083';
const SELECTION_POOL = ['s-a', 's-b', 's-c', 's-d', 's-e'];

export default function () {
  // Fan the userId space so the sliding-window keys don't all collide on one Redis sorted
  // set; a single hot key would short-circuit the realistic concurrency we measure.
  const userId = `u-${__VU}-${__ITER % 1000}`;
  const betId = `b-${__VU}-${__ITER}`;
  const body = JSON.stringify({
    userId,
    betId,
    stake: { amount: 10000, currency: 'KRW' },
    selectionIds: [SELECTION_POOL[__ITER % SELECTION_POOL.length]],
  });
  const res = http.post(`${BASE}/internal/v1/risk/check`, body, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'risk_check' },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'approved field present': (r) => r.json('approved') !== undefined,
  });
}
