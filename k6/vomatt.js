// Load test with the accounts LoadTestAccountSeeder wrote (loadtest-tokens.csv).
// Writes touch only Polls the load-test accounts create here, so deleting those accounts cleans everything up.
//   k6 run -e BASE_URL=https://<api-domain> -e VUS=50 -e DURATION=5m k6/vomatt.js
// On Railway, Dockerfile.loadtest runs it with the service's variables (k6 reads them as __ENV).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const POLLS = Number(__ENV.POLLS || 20);

const accounts = new SharedArray('accounts', () =>
  open(__ENV.TOKENS || '../loadtest-tokens.csv').trim().split('\n').slice(1).map((line) => {
    const [userId, token] = line.split(',');
    return { userId, token };
  }));

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '2m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
  },
};

const auth = (a) => ({ headers: { Authorization: `Bearer ${a.token}`, 'Content-Type': 'application/json' } });

// Each Poll by a different account, so no one hits the per-user limits
export function setup() {
  const endTime = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();
  const polls = [];
  for (let i = 0; i < POLLS; i++) {
    const a = accounts[i % accounts.length];
    const res = http.post(`${BASE}/api/votes`, JSON.stringify({
      title: `[loadtest] poll ${i + 1}`,
      audience: 'PUBLIC',
      endTime,
      options: [{ text: 'A' }, { text: 'B' }, { text: 'C' }],
    }), auth(a));
    check(res, { 'poll created': (r) => r.status === 201 });
    if (res.status === 201) {
      const v = res.json('data');
      polls.push({ id: v.id, optionIds: v.options.map((o) => o.id) });
    }
  }
  return { polls };
}

export default function ({ polls }) {
  const a = accounts[(__VU - 1) % accounts.length];
  const poll = polls[Math.floor(Math.random() * polls.length)];

  check(http.get(`${BASE}/api/votes?limit=20`, auth(a)), { 'feed 200': (r) => r.status === 200 });
  check(http.get(`${BASE}/api/votes/${poll.id}`, auth(a)), { 'poll 200': (r) => r.status === 200 });
  check(http.get(`${BASE}/api/votes/${poll.id}/comments`, auth(a)), { 'comments 200': (r) => r.status === 200 });

  const option = poll.optionIds[Math.floor(Math.random() * poll.optionIds.length)];
  check(http.post(`${BASE}/api/votes/${poll.id}/vote`, JSON.stringify({ optionIds: [option] }), auth(a)),
    { 'vote not 5xx': (r) => r.status < 500 });

  if (Math.random() < 0.3) {
    check(http.post(`${BASE}/api/votes/${poll.id}/comments`, JSON.stringify({ text: `loadtest comment ${Date.now()}` }), auth(a)),
      { 'comment 201': (r) => r.status === 201 || r.status === 200 });
  }
  sleep(1 + Math.random() * 2);
}
