// Flash sale load test.
//
// Two traffic streams at a constant arrival rate (k6 starts requests on schedule even if the
// server slows down, so the measured rate is the real throughput):
//   browse   - GET /api/v1/flash-sale/current
//   purchase - POST /api/v1/flash-sale/items/{id}/purchase with a fresh Idempotency-Key
//
// 409 SOLD_OUT / ALREADY_PURCHASED_TODAY are correct answers in a flash sale, not failures.
// Only 5xx and timeouts count as errors.
//
// Run (stack already up):  ./load-test/run.sh

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx:80';
const USERS = Number(__ENV.USERS || 300);
const BROWSE_RPS = Number(__ENV.BROWSE_RPS || 350);
const PURCHASE_RPS = Number(__ENV.PURCHASE_RPS || 250);
const DURATION = __ENV.DURATION || '60s';

const serverErrors = new Rate('server_errors');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: BROWSE_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
    purchase: {
      executor: 'constant-arrival-rate',
      exec: 'purchase',
      rate: PURCHASE_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    server_errors: ['rate<0.001'],
    'http_req_duration{scenario:browse}': ['p(95)<200'],
    'http_req_duration{scenario:purchase}': ['p(95)<200'],
  },
};

// Logs in the demo users once (BCrypt is deliberately slow) and picks the items on sale now.
export function setup() {
  const tokens = [];
  const batch = 20;
  for (let start = 1; start <= USERS; start += batch) {
    const requests = [];
    for (let n = start; n < start + batch && n <= USERS; n++) {
      const email = `user${String(n).padStart(4, '0')}@flashsale.local`;
      requests.push(['POST', `${BASE}/api/v1/auth/login`,
        JSON.stringify({ identifier: email, password: 'User@12345' }),
        { headers: { 'Content-Type': 'application/json' } }]);
    }
    for (const res of http.batch(requests)) {
      if (res.status === 200) {
        tokens.push(res.json('accessToken'));
      }
    }
  }
  const current = http.get(`${BASE}/api/v1/flash-sale/current`,
    { headers: { Authorization: `Bearer ${tokens[0]}` } }).json();
  if (!current.slot) {
    throw new Error('No flash sale slot is open right now');
  }
  return { tokens, items: current.slot.items.map((i) => i.itemId) };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function browse(data) {
  const res = http.get(`${BASE}/api/v1/flash-sale/current`, {
    headers: { Authorization: `Bearer ${pick(data.tokens)}` },
    tags: { name: 'current' },
  });
  serverErrors.add(res.status >= 500 || res.status === 0);
  check(res, { 'browse 200': (r) => r.status === 200 });
}

export function purchase(data) {
  const res = http.post(`${BASE}/api/v1/flash-sale/items/${pick(data.items)}/purchase`, null, {
    headers: {
      Authorization: `Bearer ${pick(data.tokens)}`,
      'Idempotency-Key': `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`,
    },
    tags: { name: 'purchase' },
  });
  serverErrors.add(res.status >= 500 || res.status === 0);
  check(res, {
    'purchase answered (201 bought / 409 sold out or already bought)':
      (r) => r.status === 201 || r.status === 409,
  });
}
