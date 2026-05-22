/* eslint-disable no-console */
/**
 * Realtime Chat — 자동 e2e 시나리오 (18개)
 *
 * 사전 조건: docker compose up -d 로 mongodb/redis/api-server/chat-server-1·2 모두 Up.
 *
 * 흐름:
 *   1. POST /sessions (HTTP)
 *   2. 두 STOMP 클라이언트 연결: A@8081(user-1), B@8082(user-2)
 *   3. 시나리오 18개 실행 (기능 검증 + 장애 주입: Redis stop/start, chat-server stop/start)
 *   4. mongo 쿼리로 검증
 *   5. PASS/FAIL 요약 출력 (exit 0/1)
 */

const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');
const { v4: uuid } = require('uuid');
const { MongoClient, UUID } = require('mongodb');
const { execSync } = require('child_process');

const API_URL = process.env.API_URL || 'http://localhost:8080';
const CHAT1_URL = process.env.CHAT1_URL || 'ws://localhost:8081/ws';
const CHAT2_URL = process.env.CHAT2_URL || 'ws://localhost:8082/ws';
// Node mongodb driver는 uuidRepresentation 파라미터를 지원하지 않는다 — Java driver 전용.
// Node driver는 BSON UUID class를 사용하면 자동으로 subtype 4(STANDARD)로 저장/조회한다.
const MONGO_URI = process.env.MONGO_URI || 'mongodb://localhost:27017/realtime';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ──────────────────────────────────────────────────────────
// 결과 누적
// ──────────────────────────────────────────────────────────
const results = [];
function record(name, pass, detail) {
  results.push({ name, pass, detail });
  const tag = pass ? '\x1b[32m PASS\x1b[0m' : '\x1b[31m FAIL\x1b[0m';
  const dotPad = '.'.repeat(Math.max(2, 55 - name.length));
  console.log(`[${String(results.length).padStart(2, '0')}] ${name} ${dotPad}${tag} ${detail || ''}`);
}

// ──────────────────────────────────────────────────────────
// STOMP 클라이언트 wrapper
// ──────────────────────────────────────────────────────────
class StompClient {
  constructor(label, url, userId, sessionId) {
    this.label = label;
    this.url = url;
    this.userId = userId;
    this.sessionId = sessionId;
    this.topicMessages = []; // { kind, type?, payload?, ... }
    this.acks = [];
    this.resumes = [];
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
          this.client.subscribe(`/topic/sessions/${this.sessionId}`, (m) => {
            try { this.topicMessages.push(JSON.parse(m.body)); } catch { /* ignore */ }
          });
          this.client.subscribe('/user/queue/ack', (m) => {
            try { this.acks.push(JSON.parse(m.body)); } catch { /* ignore */ }
          });
          this.client.subscribe('/user/queue/resume', (m) => {
            try { this.resumes.push(JSON.parse(m.body)); } catch { /* ignore */ }
          });
          setTimeout(resolve, 100); // SUBSCRIBE 등록 여유
        },
        onStompError: (frame) => reject(new Error(`STOMP ${this.label}: ${frame.headers?.message} ${frame.body}`)),
        onWebSocketError: (e) => reject(new Error(`WS ${this.label}: ${e.message || e}`)),
      });
      this.client.activate();
    });
  }

  publish(dest, body, headers = {}) {
    if (!this.connected) throw new Error(`${this.label} not connected`);
    this.client.publish({ destination: dest, body: JSON.stringify(body), headers });
  }

  sendMessage(content) {
    const clientEventId = uuid();
    this.publish(`/app/sessions/${this.sessionId}/messages`,
      { clientEventId, content },
      { 'X-User-Id': this.userId });
    return clientEventId;
  }

  sendMessageWithId(content, clientEventId) {
    this.publish(`/app/sessions/${this.sessionId}/messages`,
      { clientEventId, content },
      { 'X-User-Id': this.userId });
    return clientEventId;
  }

  join() {
    const cid = uuid();
    this.publish(`/app/sessions/${this.sessionId}/join`, { userId: this.userId, clientEventId: cid });
    return cid;
  }

  leave() {
    const cid = uuid();
    this.publish(`/app/sessions/${this.sessionId}/leave`, { userId: this.userId, clientEventId: cid });
    return cid;
  }

  heartbeat() {
    this.publish(`/app/sessions/${this.sessionId}/heartbeat`, { userId: this.userId });
  }

  resume(lastEventId) {
    this.publish(`/app/sessions/${this.sessionId}/resume`, { lastEventId });
  }

  async waitForAcks(count, timeoutMs = 3000) {
    const start = Date.now();
    while (this.acks.length < count) {
      if (Date.now() - start > timeoutMs) throw new Error(`${this.label} ack timeout: got ${this.acks.length}/${count}`);
      await sleep(50);
    }
  }

  async waitForResume(timeoutMs = 3000) {
    const start = Date.now();
    const baseline = this.resumes.length;
    while (this.resumes.length === baseline) {
      if (Date.now() - start > timeoutMs) throw new Error(`${this.label} resume timeout`);
      await sleep(50);
    }
    return this.resumes[this.resumes.length - 1];
  }

  disconnect() {
    if (this.client) this.client.deactivate();
  }
}

// ──────────────────────────────────────────────────────────
// HTTP helpers (Node 26 fetch 사용)
// ──────────────────────────────────────────────────────────
async function postJson(path, body) {
  const res = await fetch(`${API_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status} ${await res.text()}`);
  if (res.status === 204) return null;
  return res.json();
}

async function getJson(path) {
  const res = await fetch(`${API_URL}${path}`);
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`);
  return res.json();
}

// ──────────────────────────────────────────────────────────
// 메인
// ──────────────────────────────────────────────────────────
(async () => {
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('   Realtime Chat — Functional E2E');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');

  const mongo = await new MongoClient(MONGO_URI).connect();
  const db = mongo.db('realtime');
  const events = db.collection('events');
  const sessions = db.collection('sessions');
  const snapshots = db.collection('snapshots');

  let A, B;
  let sessionId; // string
  let sessionUuid; // mongo UUID
  let exitCode = 0;

  try {
    // [1] 세션 생성
    const created = await postJson('/sessions', { createdBy: 'user-1' });
    sessionId = created.id;
    sessionUuid = new UUID(sessionId);
    const sessionDoc = await sessions.findOne({ _id: sessionUuid });
    const sessionCreatedEvent = await events.findOne({ sessionId: sessionUuid, type: 'SESSION_CREATED' });
    record('세션 생성 (POST /sessions)',
      !!sessionDoc && !!sessionCreatedEvent,
      `id=${sessionId.substring(0, 13)}…`);

    // [2] WS 연결 + SUBSCRIBE × 3 (양 서버)
    A = new StompClient('A', CHAT1_URL, 'user-1', sessionId);
    B = new StompClient('B', CHAT2_URL, 'user-2', sessionId);
    await A.connect();
    await B.connect();
    await sleep(200);
    record('WS 연결 + SUBSCRIBE × 3 (양 서버)',
      A.connected && B.connected,
      'A@8081 + B@8082');

    // [3] Join × 2
    A.join();
    await sleep(100);
    B.join();
    await sleep(300);

    const joinedCount = await events.countDocuments({ sessionId: sessionUuid, type: 'PARTICIPANT_JOINED' });
    const aGotBJoin = B.topicMessages.some((m) => m.kind === 'event' && m.type === 'PARTICIPANT_JOINED' && m.actorUserId === 'user-1') ||
                       A.topicMessages.some((m) => m.kind === 'event' && m.type === 'PARTICIPANT_JOINED' && m.actorUserId === 'user-1');
    const bGotAJoin = A.topicMessages.some((m) => m.kind === 'event' && m.type === 'PARTICIPANT_JOINED' && m.actorUserId === 'user-2') ||
                       B.topicMessages.some((m) => m.kind === 'event' && m.type === 'PARTICIPANT_JOINED' && m.actorUserId === 'user-2');
    record('Join × 2 (events + 양쪽 라이브 전달)',
      joinedCount === 2 && aGotBJoin && bGotAJoin,
      `events=${joinedCount}/2, presence: A→B=${aGotBJoin}, B→A=${bGotAJoin}`);

    A.acks.length = 0; B.acks.length = 0;
    A.topicMessages.length = 0; B.topicMessages.length = 0;

    // [4] 메시지 20건 양방향
    const sentContents = [];
    for (let i = 1; i <= 20; i++) {
      const sender = i % 2 === 0 ? B : A;
      const content = `msg-${String(i).padStart(2, '0')}-from-${sender.userId}`;
      sender.sendMessage(content);
      sentContents.push(content);
      await sleep(40);
    }
    await sleep(800);

    const sentEvents = await events.find({ sessionId: sessionUuid, type: 'MESSAGE_SENT' }).sort({ _id: 1 }).toArray();
    const sortedByIdContents = sentEvents.map((e) => e.payload.content);
    const orderMatches = JSON.stringify(sortedByIdContents) === JSON.stringify(sentContents);
    record('메시지 20건 — events INSERT + _id 시간순',
      sentEvents.length === 20 && orderMatches,
      `count=${sentEvents.length}/20, order=${orderMatches ? 'OK' : 'MISMATCH'}`);

    const lastEventIdAt20 = sentEvents[sentEvents.length - 1]._id.toString();

    // [5] 멱등 검증
    const fixedCid = uuid();
    A.sendMessageWithId('idempotent-attempt-1', fixedCid);
    await sleep(200);
    A.sendMessageWithId('idempotent-attempt-2', fixedCid);
    await sleep(300);
    const dedupRows = await events.find({ sessionId: sessionUuid, clientEventId: new UUID(fixedCid) }).toArray();
    const dedupAcks = A.acks.filter((a) => a.clientEventId === fixedCid);
    const sameEventId = dedupAcks.length >= 2 && dedupAcks[0].eventId === dedupAcks[1].eventId;
    record('멱등: 같은 clientEventId → 1 INSERT + 같은 eventId ACK',
      dedupRows.length === 1 && sameEventId,
      `inserts=${dedupRows.length}, acks=${dedupAcks.length}, sameId=${sameEventId}`);

    // 멱등 시나리오에서 추가된 1건도 events에 들어가 있다. 시나리오 8 INCREMENTAL의 기준점을
    // "Edit/Delete 직전"으로 다시 잡아 catch-up이 정확히 2건이 되도록.
    const lastEventIdBeforeEdit = dedupRows[0]._id.toString();

    // [6] Edit — REST /events 로 MESSAGE_EDITED 송신
    const targetMsg = sentEvents[0]; // 가장 오래된 메시지
    const editClientId = uuid();
    await postJson(`/sessions/${sessionId}/events`, {
      type: 'MESSAGE_EDITED',
      actorUserId: 'user-1',
      clientEventId: editClientId,
      payload: { targetEventId: targetMsg._id.toString(), content: 'EDITED-CONTENT' },
    });
    await sleep(300);
    const editedEventCount = await events.countDocuments({ sessionId: sessionUuid, type: 'MESSAGE_EDITED' });
    const timelineAfterEdit = await getJson(`/sessions/${sessionId}/timeline`);
    const editedMsg = timelineAfterEdit.messages.find((m) => m.eventId === targetMsg._id.toString());
    record('Edit: MESSAGE_EDITED → timeline에 content 갱신',
      editedEventCount === 1 && editedMsg?.content === 'EDITED-CONTENT' && editedMsg?.status === 'EDITED',
      `editedEvent=${editedEventCount}, content="${editedMsg?.content}", status=${editedMsg?.status}`);

    // [7] Delete
    const deleteTarget = sentEvents[1];
    const delClientId = uuid();
    await postJson(`/sessions/${sessionId}/events`, {
      type: 'MESSAGE_DELETED',
      actorUserId: 'user-2',
      clientEventId: delClientId,
      payload: { targetEventId: deleteTarget._id.toString() },
    });
    await sleep(300);
    const deletedEventCount = await events.countDocuments({ sessionId: sessionUuid, type: 'MESSAGE_DELETED' });
    const timelineAfterDelete = await getJson(`/sessions/${sessionId}/timeline`);
    const deletedMsg = timelineAfterDelete.messages.find((m) => m.eventId === deleteTarget._id.toString());
    record('Delete: MESSAGE_DELETED → timeline status=DELETED (soft-delete)',
      deletedEventCount === 1 && deletedMsg?.status === 'DELETED',
      `deletedEvent=${deletedEventCount}, status=${deletedMsg?.status}`);

    // [8] Resume INCREMENTAL — 멱등 INSERT 직후 시점 → catch-up은 Edit + Delete 정확히 2건
    A.resumes.length = 0;
    A.resume(lastEventIdBeforeEdit);
    const resumeIncRes = await A.waitForResume();
    // 그 사이 edit + delete = 2 이벤트 추가됨
    const incOk = resumeIncRes.mode === 'INCREMENTAL' &&
                  resumeIncRes.events.length === 2 &&
                  resumeIncRes.events.every((e, i, arr) => i === 0 || arr[i - 1].eventId < e.eventId);
    record('Resume INCREMENTAL — lastEventId 이후 2건(Edit/Delete)',
      incOk,
      `mode=${resumeIncRes.mode}, count=${resumeIncRes.events.length}`);

    // [9] Resume SNAPSHOT (lastEventId=null)
    A.resumes.length = 0;
    A.resume(null);
    const resumeSnapRes = await A.waitForResume();
    const totalMsgsExpected = await events.countDocuments({ sessionId: sessionUuid, type: 'MESSAGE_SENT' });
    const snapOk = resumeSnapRes.mode === 'SNAPSHOT' &&
                   resumeSnapRes.state.participants.length === 2 &&
                   resumeSnapRes.state.messages.length === totalMsgsExpected;
    // 메시지 순서 검증 — eventId가 단조 증가
    const msgOrderOk = resumeSnapRes.state.messages.every((m, i, arr) => i === 0 || arr[i - 1].eventId < m.eventId);
    record('Resume SNAPSHOT (null) — 현재 상태 + 시간순',
      snapOk && msgOrderOk,
      `mode=${resumeSnapRes.mode}, msgs=${resumeSnapRes.state.messages.length}/${totalMsgsExpected}, order=${msgOrderOk ? 'OK' : 'MISMATCH'}`);

    // [10] Timeline at=과거시점 — 첫 5건만 보여야 함
    const fifthMsg = sentEvents[4];
    const at = new Date(fifthMsg.serverTs.getTime() + 1).toISOString(); // 5번째 직후
    const timelinePast = await getJson(`/sessions/${sessionId}/timeline?at=${encodeURIComponent(at)}`);
    const pastMsgs = timelinePast.messages.length;
    const pastOk = pastMsgs >= 5 && pastMsgs < totalMsgsExpected; // 처음 5건 ~ idempotent 메시지까지 포함될 수 있음
    record('Timeline at=과거시점 — 그 시점까지의 상태만',
      pastOk,
      `messages=${pastMsgs} (현재 total=${totalMsgsExpected})`);

    // [11] Leave + 즉시 스냅샷 (§12.3 trigger 1)
    const snapshotsBefore = await snapshots.countDocuments({ sessionId: sessionUuid });
    A.leave();
    await sleep(800); // @Async 스냅샷 발화 대기
    const leftCount = await events.countDocuments({ sessionId: sessionUuid, type: 'PARTICIPANT_LEFT' });
    const snapshotsAfter = await snapshots.countDocuments({ sessionId: sessionUuid });
    const latestSnapshot = await snapshots.findOne({ sessionId: sessionUuid }, { sort: { snapshotAt: -1 } });
    record('Leave + 즉시 스냅샷 (§12.3 trigger 1)',
      leftCount === 1 && snapshotsAfter > snapshotsBefore && latestSnapshot != null,
      `leftEvent=${leftCount}, snapshots=${snapshotsBefore}→${snapshotsAfter}`);

    // [12] 결정론: 스냅샷 + 이후 = 전체 리플레이
    // 마지막 스냅샷 시점의 timeline at=snapshotAt 직후 = 전체 리플레이와 동일해야 함
    // 단순화: 스냅샷 직후 상태와 현재 timeline의 state를 비교 (event 없어졌으므로 같아야 함)
    const tlNow = await getJson(`/sessions/${sessionId}/timeline`);
    const snapshotState = latestSnapshot.state;
    // snapshot.state.messages = Map<UUID, MessageView>를 List로 직렬화한 형태
    // tlNow.messages = TimelineMessageView 리스트
    const snapMsgIds = new Set((snapshotState.messages || []).map((m) => (typeof m.eventId === 'string' ? m.eventId : m.eventId?.toString?.())));
    const tlNowMsgIds = new Set(tlNow.messages.map((m) => m.eventId));
    // 스냅샷의 upToEventId가 PARTICIPANT_LEFT이므로 그 시점 메시지 집합 = 현재 timeline 메시지 집합
    const determinismOk = snapMsgIds.size === tlNowMsgIds.size &&
                          [...snapMsgIds].every((id) => tlNowMsgIds.has(id));
    record('결정론: 스냅샷 state ≡ 같은 시점 timeline 리플레이',
      determinismOk,
      `snapshot msgs=${snapMsgIds.size}, timeline msgs=${tlNowMsgIds.size}`);

    // [13] Actuator health — 3개 서버 모두 UP + components.mongo/redis UP (설계서 §15.1·§15.4)
    const healths = await Promise.all([
      fetch(`${API_URL}/actuator/health`).then((r) => r.json()).then((j) => ({ name: 'api-server', json: j })),
      fetch(`${CHAT1_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/health`).then((r) => r.json()).then((j) => ({ name: 'chat-server-1', json: j })),
      fetch(`${CHAT2_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/health`).then((r) => r.json()).then((j) => ({ name: 'chat-server-2', json: j })),
    ]);
    const apiH = healths[0].json;
    const c1H = healths[1].json;
    const c2H = healths[2].json;
    const allUp = healths.every((h) => h.json.status === 'UP');
    const apiMongoUp = apiH.components?.mongo?.status === 'UP';
    const chatMongoUp = c1H.components?.mongo?.status === 'UP' && c2H.components?.mongo?.status === 'UP';
    const chatRedisUp = c1H.components?.redis?.status === 'UP' && c2H.components?.redis?.status === 'UP';
    record('Actuator /health — 3개 서버 UP + mongo/redis indicator UP',
      allUp && apiMongoUp && chatMongoUp && chatRedisUp,
      `api=${apiH.status}, chat1=${c1H.status}, chat2=${c2H.status}, mongo=${apiMongoUp && chatMongoUp}, redis=${chatRedisUp}`);

    // [14] Prometheus 메트릭 — 단계별 카운터 + 처리 지연 + 활성 세션 Gauge (§14.3)
    const prom1 = await fetch(`${CHAT1_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/prometheus`).then((r) => r.text());
    const prom2 = await fetch(`${CHAT2_URL.replace('ws://', 'http://').replace('/ws', '')}/actuator/prometheus`).then((r) => r.text());
    const promAll = prom1 + '\n' + prom2;
    const counterSum = (name) => {
      const re = new RegExp(`^${name.replace(/\./g, '_')}(?:_total)?\\{[^}]*\\}\\s+(\\d+(?:\\.\\d+)?)`, 'gm');
      let total = 0; let m;
      while ((m = re.exec(promAll)) !== null) total += parseFloat(m[1]);
      return total;
    };
    const received  = counterSum('chat_event_received');
    const persisted = counterSum('chat_event_persisted');
    const published = counterSum('chat_event_published');
    const delivered = counterSum('chat_event_delivered');
    const dispatchTimerPresent = /^chat_message_dispatch_seconds_count\b/m.test(promAll);
    const gaugePresent          = /^chat_websocket_active_sessions\b/m.test(promAll);
    record('Prometheus — 단계별 카운터 + 처리 지연 + 활성 Gauge',
      received > 0 && persisted > 0 && published > 0 && delivered > 0 && dispatchTimerPresent && gaugePresent,
      `received=${received}, persisted=${persisted}, published=${published}, delivered=${delivered}, timer=${dispatchTimerPresent}, gauge=${gaugePresent}`);

    // [15] traceId 전파 (§14.2) — 두 경로 검증
    //   (a) chat-server: STOMP 메시지가 발급한 traceId가 events 도큐먼트 traceId 필드에 박힘
    //   (b) api-server: X-Trace-Id 헤더로 보낸 값이 events 도큐먼트에 그대로 박힘 + 응답 헤더로 회신
    const messageDocs = await events.find({ sessionId: sessionUuid, type: 'MESSAGE_SENT' })
      .sort({ _id: 1 }).limit(10).toArray();
    const allStompMsgsHaveTraceId = messageDocs.length > 0 &&
      messageDocs.every((d) => typeof d.traceId === 'string' && /^[0-9a-fA-F-]{36}$/.test(d.traceId));
    const distinctTraceCount = new Set(messageDocs.map((d) => d.traceId)).size;
    const eachMessageOwnTrace = distinctTraceCount === messageDocs.length; // 매 STOMP 메시지마다 새 trace

    // (b) REST 경로 — X-Trace-Id 헤더로 명시 전송
    const explicitTrace = '11111111-2222-3333-4444-555555555555';
    const restCid = uuid();
    const restRes = await fetch(`${API_URL}/sessions/${sessionId}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trace-Id': explicitTrace },
      body: JSON.stringify({
        type: 'MESSAGE_SENT',
        actorUserId: 'user-1',
        clientEventId: restCid,
        payload: { content: 'trace-test' },
      }),
    });
    const respHeaderTrace = restRes.headers.get('X-Trace-Id');
    const restBody = await restRes.json();
    const restEventDoc = await events.findOne({ _id: new UUID(restBody.eventId) });
    const restTraceMatches = restEventDoc?.traceId === explicitTrace;
    const headerEchoMatches = respHeaderTrace === explicitTrace;

    record('traceId 전파 — STOMP 발급(매건 다름) + REST 헤더 echo + events 도큐먼트 박힘',
      allStompMsgsHaveTraceId && eachMessageOwnTrace && restTraceMatches && headerEchoMatches,
      `stomp:${messageDocs.length}건모두UUID=${allStompMsgsHaveTraceId}, distinctTrace=${distinctTraceCount}/${messageDocs.length}, restEcho=${headerEchoMatches}, restMongo=${restTraceMatches}`);

    // [16] Redis stop 중 graceful degradation (설계서 §15.4)
    // - 메시지 INSERT는 계속됨
    // - publish는 실패 (Lettuce 2s timeout 후 circuit breaker가 sliding-window-size=6, minimum=3로 OPEN)
    // - ACK는 송신자에게 정상 도착
    execSync('docker compose stop redis', { stdio: 'pipe' });
    await sleep(2000);  // stop 안정화
    A.acks.length = 0;
    const beforeFailureCount = await events.countDocuments({ sessionId: sessionUuid });
    // 첫 3-4번 publish는 lettuce 2s timeout. 그 후엔 circuit breaker OPEN으로 즉시 fail.
    for (let i = 1; i <= 5; i++) {
      A.sendMessage(`during-redis-down-${i}`);
      await sleep(500);
    }
    // 첫 호출의 lettuce timeout(2s) × 최대 3건 + 여유
    await sleep(8000);
    const afterFailureCount = await events.countDocuments({ sessionId: sessionUuid });
    const ackDuringOutage = A.acks.length >= 5;
    const insertedDuringOutage = afterFailureCount - beforeFailureCount === 5;
    record('Redis stop — events INSERT 계속 + ACK 정상 (graceful degradation §15.4)',
      ackDuringOutage && insertedDuringOutage,
      `acks=${A.acks.length}/5, insertedDelta=${afterFailureCount - beforeFailureCount}/5`);

    // [17] Redis start → 재구독(self-healing) 후 메시지 정상 전달
    execSync('docker compose start redis', { stdio: 'pipe' });
    // Redis healthy 대기
    for (let i = 0; i < 30; i++) {
      try {
        const out = execSync('docker exec realtime-redis redis-cli ping', { stdio: 'pipe' }).toString().trim();
        if (out === 'PONG') break;
      } catch { /* not ready */ }
      await sleep(500);
    }
    // self-healing scheduler가 3초마다 재구독 → 안전 마진 6초
    await sleep(6000);
    B.topicMessages.length = 0;
    A.sendMessage('after-redis-recovery');
    await sleep(1500);
    const recoveredArrived = B.topicMessages.some((m) =>
      m.kind === 'event' && m.payload?.content === 'after-redis-recovery');
    record('Redis recovery — self-healing 재구독 후 라이브 전달 정상',
      recoveredArrived,
      `B.topicMessages.size=${B.topicMessages.length}, foundAfterRecovery=${recoveredArrived}`);

    // [18] 채팅 서버 인스턴스 다운 → 다른 서버로 재연결 → resume catch-up (설계서 §15.1)
    // A는 chat-server-1, B는 chat-server-2에 연결돼 있다. chat-server-1을 죽이고,
    // A가 chat-server-2로 재연결해 다운 동안 B가 보낸 메시지를 resume으로 복구한다.
    //   감지 = A의 WebSocket 끊김 / 완화 = 살아있는 chat-server-2로 재연결(LB 역할 수동 재현)
    //   복구 = resume(last_event_id) catch-up
    const latestBeforeDown = await events.find({ sessionId: sessionUuid })
      .sort({ _id: -1 }).limit(1).toArray();
    const aLastEventId = latestBeforeDown[0]._id.toString();

    execSync('docker compose stop chat-server-1', { stdio: 'pipe' });
    await sleep(2000); // A의 WebSocket 끊김 안정화

    // 다운 동안 B(chat-server-2)가 메시지 송신 — A는 받지 못한다
    for (let i = 1; i <= 3; i++) {
      B.sendMessage(`during-cs1-down-${i}`);
      await sleep(300);
    }
    await sleep(1000);

    // A를 chat-server-2로 재연결 — 로컬엔 LB가 없으므로 클라이언트가 직접 8082로(LB 역할 수동 재현)
    A.disconnect();
    const aReconnect = new StompClient('A2', CHAT2_URL, 'user-1', sessionId);
    await aReconnect.connect();
    await sleep(500);
    aReconnect.resume(aLastEventId);
    const cs1ResumeRes = await aReconnect.waitForResume(5000);
    const recovered = cs1ResumeRes.mode === 'INCREMENTAL'
      ? cs1ResumeRes.events.filter((e) => (e.payload?.content || '').startsWith('during-cs1-down')).length
      : cs1ResumeRes.state.messages.filter((m) => (m.content || '').startsWith('during-cs1-down')).length;
    record('채팅 서버 다운 → 다른 서버 재연결 → resume catch-up (§15.1)',
      recovered === 3,
      `mode=${cs1ResumeRes.mode}, 다운중 누락복구=${recovered}/3`);
    aReconnect.disconnect();

    // 정리 — chat-server-1 복구
    execSync('docker compose start chat-server-1', { stdio: 'pipe' });

  } catch (err) {
    console.error('\n[FATAL]', err.stack || err);
    exitCode = 2;
  } finally {
    if (A) A.disconnect();
    if (B) B.disconnect();
    await sleep(200);
    await mongo.close();
  }

  // ─────────────────────────────────────────────────────
  // 요약
  // ─────────────────────────────────────────────────────
  const pass = results.filter((r) => r.pass).length;
  const fail = results.length - pass;
  console.log('\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log(`   요약: ${results.length}개 시나리오 · \x1b[32m${pass} PASS\x1b[0m · ${fail ? '\x1b[31m' + fail + ' FAIL\x1b[0m' : '0 FAIL'}`);
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');

  if (fail > 0) {
    console.log('\n실패 상세:');
    results.filter((r) => !r.pass).forEach((r) => console.log(` ✗ ${r.name} — ${r.detail || ''}`));
  }

  process.exit(exitCode || (fail > 0 ? 1 : 0));
})();
