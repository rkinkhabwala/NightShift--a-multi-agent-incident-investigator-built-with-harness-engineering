// Steady shop traffic for the NightShift target system.
// Constant arrival rate (K6_RATE req/s, default 8) with a realistic mix:
//   ~70 % create order, ~25 % read a recent order, ~5 % requests that fail for business reasons
//   (out of stock 409, unknown SKU 422, malformed 400, payment declined 402).
// The mix keeps a small, steady client-error baseline; server errors come only from faults.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://gateway:8080';
const RATE = parseInt(__ENV.K6_RATE || '8', 10);

export const options = {
  scenarios: {
    shop: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.K6_DURATION || '720h',
      preAllocatedVUs: 20,
      maxVUs: 60,
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, timeout: '15s' };
const recentIds = [];

function pick(n) { return Math.floor(Math.random() * n); }
function sku(i) { return 'SKU-' + String(i).padStart(3, '0'); }

function createOrder(customerId, items, note) {
  const body = { customerId, items };
  if (note) body.customerNote = note;
  return http.post(`${BASE}/api/orders`, JSON.stringify(body), JSON_HEADERS);
}

function normalOrder() {
  const lines = 1 + pick(3);
  const items = [];
  for (let i = 0; i < lines; i++) items.push({ sku: sku(1 + pick(50)), qty: 1 + pick(3) });
  const res = createOrder(1 + pick(1000), items, Math.random() < 0.1 ? 'please leave at the door' : null);
  check(res, { 'order accepted or rejected cleanly': (r) => r.status < 500 });
  if (res.status === 201) {
    const id = res.json('id');
    recentIds.push(id);
    if (recentIds.length > 200) recentIds.shift();
  }
}

function readOrder() {
  if (recentIds.length === 0) return normalOrder();
  const res = http.get(`${BASE}/api/orders/${recentIds[pick(recentIds.length)]}`, { timeout: '15s', tags: { name: 'GET /api/orders/{id}' } });
  check(res, { 'read ok': (r) => r.status === 200 });
}

function badRequest() {
  const r = Math.random();
  if (r < 0.4) {
    createOrder(1 + pick(1000), [{ sku: 'SKU-OOS', qty: 1 }]);                 // 409 out of stock
  } else if (r < 0.7) {
    createOrder(1 + pick(1000), [{ sku: 'SKU-999', qty: 1 }]);                 // 422 unknown sku
  } else if (r < 0.9) {
    http.post(`${BASE}/api/orders`, '{"customerId":', JSON_HEADERS);           // 400 malformed
  } else {
    const items = [];
    for (let i = 0; i < 10; i++) items.push({ sku: sku(40 + i), qty: 10 });     // 402 declined
    createOrder(1 + pick(1000), items);
  }
}

export default function () {
  const r = Math.random();
  if (r < 0.70) normalOrder();
  else if (r < 0.95) readOrder();
  else badRequest();
}
