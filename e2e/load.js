/* eslint-disable no-console */
/**
 * Realtime Chat — 부하 테스트 (설계서 §18)
 *
 * 시나리오:
 *   1. 세션 생성 + join × 2
 *   2. user-1이 5초간 50ms 간격 100건 송신
 *   3. user-2가 5초간 50ms 간격 100건 송신
 *   4. 총 200건의 처리 지연(send→ACK) 분포 + UUIDv7 정렬 정합성
 *   5. 추가 누적 → GET /timeline 응답 시간 측정 → SNAPSHOT_THRESHOLD 권장값 도출
 *
 * 출력:
 *   - 클라이언트측 latency p50/p95/p99/p100
 *   - 서버측 처리 지연 (Prometheus)
 *   - 송신 순서 vs UUIDv7 정렬 순서 일치 여부
 *   - 누적 건수별 timeline 복원 시간 (1초 임계치 분기)
 *
 * 사용: ./e2e/run.sh --load  (또는 컨테이너 떠 있는 상태에서 node load.js)
 */

const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');
const { v4: uuid } = require('uuid');

const API_URL   = process.env.API_URL   || 'http://localhost:8080';
const CHAT1_URL = process.env.CHAT1_URL || 'ws://localhost:8081/ws';
const CHAT2_URL = process.env.CHAT2_URL || 'ws://localhost:8082/ws';

const PER_USER_BURST       = parseInt(process.env.LOAD_BURST_PER_USER || '100', 10);
const BURST_INTERVAL_MS    = parseInt(process.env.LOAD_BURST_INTERVAL_MS || '50', 10);
const RESTORE_STEPS        = (process.env.LOAD_RESTORE_STEPS || '2000,5000,10000,20000').split(',').map(Number);
const RESTORE_BATCH_PAUSE_MS = parseInt(process.env.LOAD_RESTORE_PAUSE_MS || '0', 10);
const ONE_SECOND_TARGET_MS = parseInt(process.env.LOAD_RESTORE_TARGET_MS || '1000', 10);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (n, dp = 1) => Number.isFinite(n) ? n.toFixed(dp) : '-';

// ────────────────────────────────────────────────────────────────
// 통계 계산
// ────────────────────────────────────────────────────────────────
function percentile(sorted, p) {
  if (sorted.length === 0) return NaN;
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}
function summary(samples) {
  if (samples.length === 0) return { n: 0 };
  const sorted = [...samples].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  return {
    n: sorted.length,
    min: sorted[0],
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
    mean: sum / sorted.length,
  };
}

// ────────────────────────────────────────────────────────────────
// STOMP latency-tracking client
// ────────────────────────────────────────────────────────────────
class LoadClient {
  constructor(label, url, userId, sessionId) {
    this.label = label;
    this.url = url;
    this.userId = userId;
    this.sessionId = sessionId;
    this.sendTimes = new Map();              // clientEventId → ms (송신 시각)
    this.ackLatencies = [];                  // ms 단위
    this.sentClientIds = [];                 // 송신 순서 보존
    this.connected = false;
  }

  async connect() {
    return new Promise((resolve, reject) => {
      this.client = new Client({
        webSocketFactory: () => new WebSocket(this.url),
        reconnectDelay: 0,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        onConnect: () => {
          this.connected = true;
          this.client.subscribe(`/topic/sessions/${this.sessionId}`, () => { /* drop topic for load */ });
          this.client.subscribe('/user/queue/ack', (m) => {
            const ack = JSON.parse(m.body);
            const sent = this.sendTimes.get(ack.clientEventId);
            if (sent !== undefined) {
              this.ackLatencies.push(Date.now() - sent);
              this.sendTimes.delete(ack.clientEventId);
            }
          });
          setTimeout(resolve, 100);
        },
        onStompError: (f) => reject(new Error(`${this.label} STOMP: ${f.headers?.message} ${f.body}`)),
        onWebSocketError: (e) => reject(new Error(`${this.label} WS: ${e.message || e}`)),
      });
      this.client.activate();
    });
  }

  join() {
    this.client.publish({
      destination: `/app/sessions/${this.sessionId}/join`,
      body: JSON.stringify({ userId: this.userId, clientEventId: uuid() }),
    });
  }

  sendMessage(content) {
    const cid = uuid();
    this.sendTimes.set(cid, Date.now());
    this.sentClientIds.push(cid);
    this.client.publish({
      destination: `/app/sessions/${this.sessionId}/messages`,
      headers: { 'X-User-Id': this.userId },
      body: JSON.stringify({ clientEventId: cid, content }),
    });
    return cid;
  }

  disconnect() { if (this.client) this.client.deactivate(); }

  async waitForAcks(timeoutMs = 30000) {
    const start = Date.now();
    while (this.sendTimes.size > 0) {
      if (Date.now() - start > timeoutMs) {
        throw new Error(`${this.label}: ${this.sendTimes.size} pending acks after ${timeoutMs}ms`);
      }
      await sleep(50);
    }
  }
}

// ────────────────────────────────────────────────────────────────
// HTTP helpers
// ────────────────────────────────────────────────────────────────
async function postJson(path, body, headers = {}) {
  const res = await fetch(`${API_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status} ${await res.text()}`);
  if (res.status === 204) return null;
  return res.json();
}

async function timeGet(path) {
  const t0 = Date.now();
  const res = await fetch(`${API_URL}${path}`);
  await res.text();   // body 받는 시간까지 포함
  return Date.now() - t0;
}

async function appendEventsBatch(sessionId, count, contentPrefix, userId, signal) {
  // REST /events로 빠르게 누적 (load 측정과 분리 — 단순히 메시지를 쌓는 용도)
  for (let i = 0; i < count; i++) {
    await postJson(`/sessions/${sessionId}/events`, {
      type: 'MESSAGE_SENT',
      actorUserId: userId,
      clientEventId: uuid(),
      payload: { content: `${contentPrefix}-${i}` },
    });
    if (RESTORE_BATCH_PAUSE_MS > 0) await sleep(RESTORE_BATCH_PAUSE_MS);
    if (signal?.aborted) return;
  }
}

// ────────────────────────────────────────────────────────────────
// 메인
// ────────────────────────────────────────────────────────────────
(async () => {
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('   Realtime Chat — Load Test (설계서 §18)');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');
  console.log(`  per-user burst: ${PER_USER_BURST} msgs × ${BURST_INTERVAL_MS}ms`);
  console.log(`  restore steps:  ${RESTORE_STEPS.join(', ')} events\n`);

  // 1. 세션 + join × 2
  const session = await postJson('/sessions', { createdBy: 'user-1' });
  const sid = session.id;
  console.log(`  session=${sid}\n`);

  const A = new LoadClient('A', CHAT1_URL, 'user-1', sid);
  const B = new LoadClient('B', CHAT2_URL, 'user-2', sid);
  await A.connect();
  await B.connect();
  A.join(); B.join();
  await sleep(500);

  // 2. user-1 burst (5s, 50ms × 100)
  console.log(`  Phase 1: A burst (${PER_USER_BURST} msgs)...`);
  const t1 = Date.now();
  for (let i = 1; i <= PER_USER_BURST; i++) {
    A.sendMessage(`A-${String(i).padStart(3, '0')}`);
    await sleep(BURST_INTERVAL_MS);
  }
  await A.waitForAcks(60000);
  const t1End = Date.now();

  // 3. user-2 burst
  console.log(`  Phase 2: B burst (${PER_USER_BURST} msgs)...`);
  const t2 = Date.now();
  for (let i = 1; i <= PER_USER_BURST; i++) {
    B.sendMessage(`B-${String(i).padStart(3, '0')}`);
    await sleep(BURST_INTERVAL_MS);
  }
  await B.waitForAcks(60000);
  const t2End = Date.now();

  // 4. 처리 지연 분포
  const allLatencies = [...A.ackLatencies, ...B.ackLatencies];
  const s = summary(allLatencies);
  const sA = summary(A.ackLatencies);
  const sB = summary(B.ackLatencies);

  console.log('\n──── 메시지 처리 지연 (client send → ACK, ms) ────');
  console.log(`${'where'.padEnd(10)} ${'n'.padStart(5)}  ${'min'.padStart(6)}  ${'p50'.padStart(6)}  ${'p95'.padStart(6)}  ${'p99'.padStart(6)}  ${'max'.padStart(6)}  ${'mean'.padStart(6)}`);
  for (const [name, x] of [['A→ack', sA], ['B→ack', sB], ['전체', s]]) {
    console.log(`${name.padEnd(10)} ${String(x.n).padStart(5)}  ${fmt(x.min).padStart(6)}  ${fmt(x.p50).padStart(6)}  ${fmt(x.p95).padStart(6)}  ${fmt(x.p99).padStart(6)}  ${fmt(x.max).padStart(6)}  ${fmt(x.mean).padStart(6)}`);
  }
  console.log(`  A burst wall: ${(t1End - t1) / 1000}s   B burst wall: ${(t2End - t2) / 1000}s   (이상적 ${(PER_USER_BURST * BURST_INTERVAL_MS) / 1000}s)`);

  // 5. UUIDv7 정렬 정합성 — Prometheus가 아니라 클라이언트 송신 순서와 mongo _id 순서를 비교
  //    REST GET /sessions/{id}/events 으로 가져와 ASC 정렬된 결과의 content 순서가 송신 순서와 일치하는지
  const allEventsResp = await fetch(`${API_URL}/sessions/${sid}/events?limit=500`).then((r) => r.json());
  const messageEvents = allEventsResp.events.filter((e) => e.type === 'MESSAGE_SENT');
  // 송신 순서: A 100건 다 보낸 다음 B 100건. mongo _id ASC = 시간순. content가 A-001...A-100,B-001...B-100 순.
  const expectedOrder = [];
  for (let i = 1; i <= PER_USER_BURST; i++) expectedOrder.push(`A-${String(i).padStart(3, '0')}`);
  for (let i = 1; i <= PER_USER_BURST; i++) expectedOrder.push(`B-${String(i).padStart(3, '0')}`);
  const actualOrder = messageEvents.slice(0, expectedOrder.length).map((e) => e.payload.content);
  const orderMatches = JSON.stringify(actualOrder) === JSON.stringify(expectedOrder);

  console.log('\n──── 순서 정합성 (UUIDv7 ASC vs 송신 순서) ────');
  console.log(`  ${orderMatches ? '✓' : '✗'} 송신 ${expectedOrder.length}건, 일치=${orderMatches}`);
  if (!orderMatches) {
    let mismatched = 0;
    for (let i = 0; i < expectedOrder.length; i++) {
      if (expectedOrder[i] !== actualOrder[i]) {
        mismatched++;
        if (mismatched <= 5) console.log(`    [${i}] expected=${expectedOrder[i]} actual=${actualOrder[i]}`);
      }
    }
    console.log(`  총 mismatch=${mismatched}/${expectedOrder.length}`);
  }

  // 6. Prometheus 처리 지연 추출 (서버측)
  // Micrometer publishPercentiles의 quantile 라인은 ring-buffer 추정이라 측정 시점에 0이 잦다.
  // 안정적인 _count/_sum으로 평균을 계산한다. percentile 분포는 위의 client-side latency가 담당.
  console.log('\n──── 서버측 처리 지연 (Prometheus chat.message.dispatch, _sum/_count 평균) ────');
  const prom1 = await fetch(`${CHAT1_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/prometheus`).then((r) => r.text());
  const prom2 = await fetch(`${CHAT2_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/prometheus`).then((r) => r.text());
  const scalar = (text, metric) => {
    const re = new RegExp(`^${metric}\\{[^}]*\\}\\s+(\\d+(?:\\.\\d+(?:[eE][+-]?\\d+)?)?)`, 'm');
    const m = re.exec(text);
    return m ? parseFloat(m[1]) : NaN;
  };
  for (const [label, txt] of [['chat-server-1', prom1], ['chat-server-2', prom2]]) {
    const count = scalar(txt, 'chat_message_dispatch_seconds_count');
    const sum = scalar(txt, 'chat_message_dispatch_seconds_sum');
    const max = scalar(txt, 'chat_message_dispatch_seconds_max');
    const avgMs = count > 0 ? (sum / count) * 1000 : NaN;
    console.log(`  ${label.padEnd(15)} count=${count || 0}  avg=${fmt(avgMs)}ms  max=${fmt(max * 1000)}ms`);
  }

  // 7. 복원 시간 측정 → 스냅샷 임계치 N 권장
  console.log(`\n──── timeline?at= 복원 시간 측정 (목표 ${ONE_SECOND_TARGET_MS}ms 분기점) ────`);
  console.log(`  현재 누적 이벤트: 약 ${PER_USER_BURST * 2 + 2}건 (메시지 + join 2건)`);
  console.log(`  추가로 ${RESTORE_STEPS.join(', ')}건까지 누적하며 timeline GET 응답 시간을 측정한다.`);
  console.log();
  console.log(`  ${'target N'.padStart(9)}  ${'restore ms'.padStart(12)}  ${'verdict'.padStart(20)}`);

  let recommendedN = null;
  let prevCumulative = PER_USER_BURST * 2;
  const measurements = []; // { n, ms }
  for (const targetTotal of RESTORE_STEPS) {
    const delta = Math.max(0, targetTotal - prevCumulative);
    if (delta > 0) {
      process.stdout.write(`  누적 ${targetTotal}건까지 +${delta}건 송신 중...\r`);
      await appendEventsBatch(sid, delta, 'L', 'user-1');
    }
    // 측정 노이즈를 줄이려 3회 측정 후 중앙값
    const samples = [];
    for (let k = 0; k < 3; k++) samples.push(await timeGet(`/sessions/${sid}/timeline`));
    samples.sort((a, b) => a - b);
    const restoreMs = samples[1];
    measurements.push({ n: targetTotal, ms: restoreMs });
    const verdict = restoreMs >= ONE_SECOND_TARGET_MS ? '🔴 임계 초과' : '🟢 양호';
    console.log(`  ${String(targetTotal).padStart(9)}  ${String(restoreMs).padStart(10)}ms  ${verdict.padStart(20)}`);
    if (recommendedN === null && restoreMs >= ONE_SECOND_TARGET_MS) {
      recommendedN = Math.floor(prevCumulative * 0.7); // 임계 초과 직전 누적 × 0.7 마진
    }
    prevCumulative = targetTotal;
  }
  console.log();
  if (recommendedN !== null) {
    console.log(`  >>> SNAPSHOT_THRESHOLD 권장값: ${recommendedN} (1초 임계치 직전 누적 × 0.7 마진)`);
  } else {
    // 측정 범위에서 1초 미도달 — 마지막 두 점으로 선형 외삽해 1초 도달 N 추정
    const last = measurements[measurements.length - 1];
    const first = measurements[0];
    const slope = (last.ms - first.ms) / Math.max(1, last.n - first.n); // ms per event
    const fixedOverhead = first.ms - slope * first.n;
    const estimatedN = slope > 0
      ? Math.round((ONE_SECOND_TARGET_MS - fixedOverhead) / slope)
      : null;
    console.log(`  >>> 측정 범위(~${last.n}건)에서 ${ONE_SECOND_TARGET_MS}ms 미도달 (최대 ${last.ms}ms).`);
    if (estimatedN && estimatedN > 0) {
      console.log(`  >>> 선형 외삽: 약 ${estimatedN.toLocaleString()}건에서 1초 도달 추정 → 권장 N ≈ ${Math.floor(estimatedN * 0.7).toLocaleString()}`);
      console.log(`      (로컬 측정이라 운영 환경(네트워크·도큐먼트 크기·샤딩)에서 재측정 필요)`);
    } else {
      console.log(`  >>> 복원 비용이 거의 평탄 — 고정 오버헤드가 지배적. 잠정 N=500은 매우 보수적.`);
    }
  }

  A.disconnect(); B.disconnect();
  await sleep(300);

  // 종합 PASS/FAIL
  // - latency p99 < 200ms 권장 (1:1, 로컬 환경 기준)
  // - 순서 정합성 일치
  const p99TargetMs = parseInt(process.env.LOAD_P99_TARGET_MS || '200', 10);
  const passes = orderMatches && s.p99 < p99TargetMs;
  console.log(`\n──── 종합 ────`);
  console.log(`  순서 정합성: ${orderMatches ? '✓' : '✗'}`);
  console.log(`  전체 p99=${fmt(s.p99)}ms (target < ${p99TargetMs}ms): ${s.p99 < p99TargetMs ? '✓' : '✗'}`);
  console.log(`  결과: ${passes ? '\x1b[32mPASS\x1b[0m' : '\x1b[31mFAIL\x1b[0m'}`);

  process.exit(passes ? 0 : 1);
})().catch((err) => {
  console.error('\n[FATAL]', err.stack || err);
  process.exit(2);
});
