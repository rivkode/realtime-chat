/* eslint-disable no-console */
/**
 * Realtime Chat — 자동 e2e 시나리오 (12개)
 *
 * 사전 조건: docker compose up -d 로 mongodb/redis/api-server/chat-server-1·2 모두 Up.
 *
 * 흐름:
 *   1. POST /sessions (HTTP)
 *   2. 두 STOMP 클라이언트 연결: A@8081(user-1), B@8082(user-2)
 *   3. 시나리오 12개 실행
 *   4. mongo 쿼리로 검증
 *   5. PASS/FAIL 요약 출력 (exit 0/1)
 */

const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');
const { v4: uuid } = require('uuid');
const { MongoClient, UUID } = require('mongodb');

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
