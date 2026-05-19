# 실시간 채팅 — 처음부터 끝까지 시나리오 (STOMP 페이로드 + Mongo 모니터링)

이 문서 하나로 (1) **개념 정리**, (2) **각 단계별 payload**, (3) **각 단계 결과를 Mongo·Redis에서 확인하는 쿼리**까지 모두 한다.

---

## A. 개념 — 한 페이지로 정리

### A-1. "연결"이라는 단어가 세 단계로 나뉜다

```
┌─────────────────────────────────────────────────────────────────┐
│  1) WebSocket 연결 (HTTP Upgrade → TCP 소켓 유지)                │
│     ws://localhost:8081/ws  ← sessionId 안 들어감               │
│                                                                  │
│  2) STOMP CONNECT (그 소켓 위의 프로토콜 핸드셰이크)              │
│     CONNECT frame                  ← sessionId 안 들어감         │
│                                                                  │
│  3) STOMP SUBSCRIBE (관심 destination 구독)                      │
│     SUBSCRIBE /topic/sessions/{SID}  ← 처음으로 sessionId 등장   │
│     SUBSCRIBE /user/queue/ack                                    │
│     SUBSCRIBE /user/queue/resume                                 │
│                                                                  │
│  4) STOMP SEND (필요할 때 액션)                                  │
│     SEND /app/sessions/{SID}/join                                │
│     SEND /app/sessions/{SID}/messages                            │
│     SEND /app/sessions/{SID}/heartbeat                           │
│     SEND /app/sessions/{SID}/resume                              │
│     SEND /app/sessions/{SID}/leave                               │
└─────────────────────────────────────────────────────────────────┘
```

### A-2. sessionId가 등장하는 곳

| 목적 | destination | 예시 |
|---|---|---|
| **라이브 메시지 받기** | `/topic/sessions/{SID}` — SUBSCRIBE | 상대가 보낸 메시지·join·leave·presence 수신 |
| **본인에게만 오는 ACK 받기** | `/user/queue/ack` — SUBSCRIBE (`{SID}` 안 들어감, 서버가 라우팅) | 송신 결과 |
| **resume 응답 받기** | `/user/queue/resume` — SUBSCRIBE | catch-up 결과 |
| **세션에 액션 보내기** | `/app/sessions/{SID}/{action}` — SEND | 모든 SEND 액션 |

### A-3. destination prefix 의미

| Prefix | 방향 | 의미 |
|---|---|---|
| `/app` | 클라이언트 → 서버 | 컨트롤러 `@MessageMapping` 호출 |
| `/topic` | 서버 → 다수 클라이언트 | 같은 destination 구독자 모두에게 broadcast |
| `/user/queue` | 서버 → 1명 (그 simpSession에만) | 본인에게만 (ACK·resume 응답) |

### A-4. body의 `userId` vs 헤더의 `X-User-Id` — 헷갈리지 말 것

| frame | 사용 위치 | 이유 |
|---|---|---|
| `SEND /messages` | **header** `X-User-Id` | 컨트롤러가 `@Header`로 받는다 |
| `SEND /join`, `/leave` | **body** `userId` | 멤버십 이벤트의 actor를 명시적으로 body에 |
| `SEND /heartbeat` | **body** `userId` | 동일 |

---

## B. 환경 변수 (모든 frame에 치환)

| 변수 | 의미 | 어디서 받나 |
|---|---|---|
| `{SID}` | 세션 UUID | `POST /sessions` 응답 `id` |
| `{USER_ID}` | 송신 유저 (예: `user-1`, `user-2`) | 직접 지정 |
| `{CEID}` | 매 액션마다 새로 발급하는 UUID — 멱등 키 | `uuidgen` 또는 https://www.uuidgenerator.net |
| `{LAST_EVENT_ID}` | resume 기준점 — 직전 ACK의 `eventId` | ACK 응답에서 복사 |

---

## C. WebSocket / STOMP frame 구조 (한 번만 봐두면 됨)

```
COMMAND\n          ← CONNECT, SUBSCRIBE, SEND, DISCONNECT
header-key:value\n
another-key:value\n
\n                 ← 헤더와 body 사이 빈 줄
body (있다면)
\0                 ← frame 끝 NUL byte
```

> **NUL byte (`\0`)** 가 없으면 Spring STOMP가 frame 끝을 인식 못 한다. 텍스트 모드 UI(websocketking 등)에서 입력이 까다로워 `stomp-tester.html`(stompjs가 자동 처리)을 권장.

---

## D. 처음부터 끝까지 시나리오 (10 STEP)

두 채팅 서버에 두 유저가 각각 붙는 §5.3 핵심 시나리오. **탭 A = user-1 @ chat-server-1(8081)**, **탭 B = user-2 @ chat-server-2(8082)**.

각 STEP은 **무엇 / 어디로 / payload / 기대 / 검증 쿼리** 형식.

---

### STEP 0 — 인프라 기동 + 세션 생성 (HTTP)

**무엇**: 5개 컨테이너 띄우고 세션 메타를 HTTP로 만든다. 세션 생성은 WebSocket의 일이 아님 (§5.1).

**액션**
```bash
docker compose down -v
docker compose up -d --build
docker compose ps   # mongodb/redis healthy, 3개 server Up 확인

SESSION_RESPONSE=$(curl -s -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -d '{"createdBy":"user-1"}')
echo $SESSION_RESPONSE
SESSION_ID=$(echo $SESSION_RESPONSE | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
echo "SESSION_ID=$SESSION_ID"
```

**검증 (Mongo)**
```javascript
// mongosh "mongodb://localhost:27017/realtime?uuidRepresentation=STANDARD"
db.sessions.find({}, {_id:1, status:1, createdBy:1}).pretty()
// 기대: { _id: UUID("..."), status: "ACTIVE", createdBy: "user-1", ... }

db.events.find({sessionId: UUID("<SESSION_ID>")}, {type:1}).pretty()
// 기대: 1건 — { type: "SESSION_CREATED", payload: { createdBy: "user-1" } }
```

---

### STEP 1 — WebSocket 연결 (1단계 — WS handshake)

**무엇**: 각 탭이 chat-server에 TCP 소켓을 열고 HTTP Upgrade로 WebSocket으로 전환. **이 단계에서 sessionId는 사용하지 않는다.**

**액션 (탭 A·B 각각)**

`stomp-tester.html`에서:
- 탭 A → URL: `ws://localhost:8081/ws`, X-User-Id: `user-1`, Session UUID: `{SID}` → **[Connect]**
- 탭 B → URL: `ws://localhost:8082/ws`, X-User-Id: `user-2`, Session UUID: `{SID}` → **[Connect]**

stompjs가 자동으로 STEP 1(WS) + STEP 2(STOMP CONNECT) + STEP 3(세 SUBSCRIBE)을 한꺼번에 수행.

**raw STOMP frame** (websocketking 등에서 직접 보낼 때):

```
CONNECT
accept-version:1.2
host:localhost
heart-beat:0,0

\0
```

**기대**: 각 탭 로그에 `★ Connected to ws://localhost:8081/ws`. 서버에서 `CONNECTED` frame 반환.

**검증**: 이 단계만으론 DB 변화 없음. chat-server 로그에 STOMP CONNECT 라인.
```bash
docker compose logs --tail 20 chat-server-1 | grep -i "stomp\|connect"
```

---

### STEP 2 — SUBSCRIBE 세 가지 (3단계 — 메시지 받을 준비)

**무엇**: 라이브 이벤트·ACK·resume 응답을 받을 destination 세 곳을 구독 등록. **`/topic/sessions/{SID}`로 sessionId가 여기서 처음 등장.** 

> stomp-tester.html은 [Connect] 직후 자동으로 세 destination을 SUBSCRIBE한다.

**raw frame 3개 (Connect 직후 보낸다)**

```
SUBSCRIBE
id:sub-topic
destination:/topic/sessions/{SID}

\0
```
```
SUBSCRIBE
id:sub-ack
destination:/user/queue/ack

\0
```
```
SUBSCRIBE
id:sub-resume
destination:/user/queue/resume

\0
```

**기대 (서버 측 부수효과, 코드 흐름)**
1. `StompLifecycleListener.onSubscribe`가 `SessionSubscribeEvent` 받음
2. destination에서 `{SID}` 파싱
3. `ConnectionRegistry.register(SID, simpSessionId, ...)` — 이 세션의 첫 연결이면 콜백 발화
4. 첫 연결이면 `SessionChannelSubscriber.subscribe(SID)` → Redis `SUBSCRIBE channel:session:{SID}`

**검증 (Redis)**
```bash
docker exec realtime-redis redis-cli PUBSUB CHANNELS 'channel:session:*'
# 기대: channel:session:<SID> 한 개 (또는 두 인스턴스가 각자 구독 중이면 동일 채널이지만 PUBSUB CHANNELS는 1개)
docker exec realtime-redis redis-cli PUBSUB NUMSUB channel:session:<SID>
# 기대: subscriber 수 (Redis 입장에서는 chat-server-1·2 둘이 구독 중이면 2)
```

---

### STEP 3 — Join (멤버십 등록)

**무엇**: user-1·user-2가 명시적으로 세션에 참여한다. WebSocket이 연결됐다고 자동으로 join되는 게 아니다 (§8.2).

**액션 (탭 A)**: HTML의 **[Join]** 클릭
- 내부적으로 SEND `/app/sessions/{SID}/join` body `{userId:"user-1", clientEventId:<uuid>}`

**raw frame**
```
SEND
destination:/app/sessions/{SID}/join
content-type:application/json

{"userId":"user-1","clientEventId":"{CEID}"}
\0
```

**body 변수만**
```json
{"userId":"user-1","clientEventId":"11111111-1111-1111-1111-111111111111"}
```

**탭 B**: 같은 방식으로 **[Join]** (user-2, 새 `clientEventId`).

**기대 결과 (시퀀스)**
1. 탭 A: `[ACK] {clientEventId:..., eventId:<UUIDv7>, type:"PARTICIPANT_JOINED", serverTs:...}` (→ Last event ID 입력란 자동 채움)
2. 탭 A·**B 모두**: 
   - `[TOPIC] {kind:"event", type:"PARTICIPANT_JOINED", actorUserId:"user-1", payload:{userId:"user-1"}, ...}`
   - `[TOPIC] {kind:"presence", status:"ONLINE", userId:"user-1", ...}`

**검증 (Mongo)**
```javascript
db.events.find({sessionId: UUID("<SID>"), type: "PARTICIPANT_JOINED"}, {actorUserId:1, _id:1}).sort({_id:1}).pretty()
// 기대: 2건 (user-1, user-2 순서)
```

**검증 (Redis presence)**
```bash
docker exec realtime-redis redis-cli KEYS 'presence:*'
# 기대: presence:<SID>:user-1, presence:<SID>:user-2

docker exec realtime-redis redis-cli TTL "presence:<SID>:user-1"
# 기대: 30 근처 (방금 SET됐으므로)
```

**§5.3 검증 포인트**: 탭 A의 [Join] 결과가 **chat-server-2에 붙은 탭 B**에도 도착했다는 것. 두 채팅 서버는 Redis Pub/Sub 채널 `channel:session:{SID}`로 연결되어 있다.

---

### STEP 4 — 메시지 송수신 (§8.1 핫패스)

**무엇**: 채팅의 본 동작. UUIDv7로 시간 정렬되는 이벤트가 events에 INSERT되고 채널로 publish된다.

**액션 (탭 A)**: Content 입력 `hello from A 1` → **[Send msg]**

**raw frame**
```
SEND
destination:/app/sessions/{SID}/messages
content-type:application/json
X-User-Id:user-1

{"clientEventId":"{CEID}","content":"hello from A 1"}
\0
```

> ⚠ `X-User-Id`는 **헤더에** 넣어야 한다 (컨트롤러가 `@Header`로 받음). body에 넣으면 actor가 `anonymous`로 잡힌다.

**body 변수만**
```json
{"clientEventId":"22222222-2222-2222-2222-222222222222","content":"hello from A 1"}
```

탭 A·B를 번갈아 5건쯤 보낸다. **각 ACK가 도착할 때마다 `Last event ID` 입력란이 갱신**되니, **5번째 메시지의 ACK 후 그 값을 따로 메모**(STEP 7 INCREMENTAL resume에 사용).
019e3dfe-6493-77f3-9615-4c692fbe5910
**기대 결과**
- 송신 탭: `[ACK]` + `[TOPIC] MESSAGE_SENT ...` (자기도 받음)
- 다른 탭: `[TOPIC] MESSAGE_SENT ...`

**검증 (Mongo) — 시간순 정렬 확인**
```javascript
db.events.find(
  {sessionId: UUID("<SID>"), type: "MESSAGE_SENT"},
  {_id:1, actorUserId:1, "payload.content":1}
).sort({_id:1}).forEach(e => print(
  e._id.toString().substring(0,13),
  e.actorUserId.padEnd(8),
  e.payload.content
))
// 기대: UUIDv7 prefix가 단조 증가, content가 송신 순서대로
```

**§9.1 멱등 검증 (선택)**: 같은 `clientEventId`로 두 번 보내려면 브라우저 콘솔에서:
```javascript
client.publish({
  destination:`/app/sessions/${document.getElementById('sid').value}/messages`,
  headers:{'X-User-Id':'user-1'},
  body:JSON.stringify({clientEventId:'DEDUP-TEST-FIXED-UUID',content:'first'})
});
client.publish({
  destination:`/app/sessions/${document.getElementById('sid').value}/messages`,
  headers:{'X-User-Id':'user-1'},
  body:JSON.stringify({clientEventId:'DEDUP-TEST-FIXED-UUID',content:'second'})
});
```
→ 두 ACK가 **같은 eventId** + Mongo에 1건만 INSERT.
```javascript
db.events.find({sessionId: UUID("<SID>"), clientEventId: UUID("DEDUP-TEST-FIXED-UUID")}).count()
// 기대: 1
```

---

### STEP 5 — Heartbeat (presence TTL 갱신)

**무엇**: presence 키의 TTL을 30초로 다시 박는다. 상태 transition이 아니므로 **publish 없음** (§8.3).

**액션**: 탭 A **[Heartbeat]** 클릭

**raw frame**
```
SEND
destination:/app/sessions/{SID}/heartbeat
content-type:application/json

{"userId":"user-1"}
\0
```

**body 변수만**
```json
{"userId":"user-1"}
```

**기대**
- 탭 A·B 어디에도 `[TOPIC]` 안 떨어짐 (heartbeat은 broadcast 안 함)
- ACK도 없음 (heartbeat은 ACK 안 보냄)

**검증 (Redis)**
```bash
docker exec realtime-redis redis-cli TTL "presence:<SID>:user-1"
# 기대: 30 근처 (방금 갱신)
```

30초 동안 heartbeat 보내지 않으면 TTL이 0으로 떨어지고 키 자동 만료 → `-2` 반환 (`KEY does not exist`).

---

### STEP 6 — Resume INCREMENTAL (§9.3 Pull 복구)

**무엇**: `lastEventId` 이후 누락된 이벤트를 받는다. catch-up 건수가 임계치(`RESUME_CATCHUP_THRESHOLD`, 기본 1000) 이하면 증분 응답.

**준비**: STEP 4에서 메모한 5번째 ACK의 `eventId`를 탭 A `Last event ID` 입력란에 그대로 둔다(자동 채워져 있음). 그 후 추가 메시지 3건 송신 (예: `hello-6, hello-7, hello-8`).

**액션**: 탭 A **[Resume (lastEventId)]** 클릭

**raw frame**
```
SEND
destination:/app/sessions/{SID}/resume
content-type:application/json

{"lastEventId":"{LAST_EVENT_ID}"}
\0
```

**body 변수만**
```json
{"lastEventId":"<5번째 ACK의 eventId>"}
```

**기대 응답 (탭 A `[RESUME]`)**
```json
{
  "mode": "INCREMENTAL",
  "events": [
    {"eventId":"<hello-6>", "type":"MESSAGE_SENT", "payload":{"content":"hello-6"}, ...},
    {"eventId":"<hello-7>", ...},
    {"eventId":"<hello-8>", ...}
  ],
  "state": null,
  "lastEventId": "<hello-8의 eventId>",
  "hasMore": false
}
```

**검증 (Mongo) — INCREMENTAL이 정확한지 확인**
```javascript
// catch-up count
const sid = UUID("<SID>");
const last = UUID("<5번째 ACK의 eventId>");
print("count_after:", db.events.countDocuments({sessionId: sid, _id: {$gt: last}}));
// 기대: 3 (hello-6/7/8)

db.events.find({sessionId: sid, _id: {$gt: last}})
  .sort({_id:1})
  .forEach(e => print(e._id.toString().substring(0,13), e.type, e.payload?.content||""));
// 기대: hello-6 → hello-7 → hello-8 시간순
```

**SNAPSHOT으로 빠진다면**: 임계치 초과 또는 lastEventId가 null로 들어간 것. 다음 명령으로 확인:
```bash
docker exec realtime-chat-server-1 env | grep -i resume
# 기대: 출력 없음 (디폴트). RESUME_CATCHUP_THRESHOLD=2 같은 게 보이면 docker-compose.override.yml 정리 필요
```

---

### STEP 7 — Resume SNAPSHOT (lastEventId=null)

**무엇**: 클라이언트가 lastEventId를 잃어버린 경우 — 스냅샷 + 잔여 이벤트 fold한 현재 상태를 받는다.

**액션**: 탭 A **[Resume (null)]** 클릭

**raw frame**
```
SEND
destination:/app/sessions/{SID}/resume
content-type:application/json

{"lastEventId":null}
\0
```

**body 변수만**
```json
{"lastEventId":null}
```

**기대 응답**
```json
{
  "mode": "SNAPSHOT",
  "events": [],
  "state": {
    "participants": ["user-1", "user-2"],
    "messages": [
      {"eventId":"<A-1>", "sender":"user-1", "content":"hello from A 1", "status":"SENT"},
      ... (모든 메시지, 시간순)
    ],
    "status": "ACTIVE"
  },
  "lastEventId": "<가장 최신 이벤트의 eventId>",
  "hasMore": false
}
```

**검증 (Mongo) — messages 순서 일치**
```javascript
const sid = UUID("<SID>");
db.events.find({sessionId: sid, type: "MESSAGE_SENT"})
  .sort({_id:1})
  .forEach(e => print(e._id.toString().substring(0,13), e.actorUserId.padEnd(8), e.payload.content));
// 응답의 messages 배열과 동일 순서여야 함 (STANDARD UUID representation 검증)
```

---

### STEP 8 — Resume SNAPSHOT 우회 (임계치 초과)

**무엇**: catch-up 대상이 너무 많으면 증분 대신 스냅샷 응답으로 전환.

**준비**: chat-server-1만 `RESUME_CATCHUP_THRESHOLD=2`로 재시작
```bash
cat > docker-compose.override.yml <<'EOF'
services:
  chat-server-1:
    environment:
      RESUME_CATCHUP_THRESHOLD: "2"
EOF
docker compose up -d chat-server-1
docker compose logs --tail 3 chat-server-1
```

**준비 (Mongo)** — 가장 오래된 메시지의 eventId 메모
```javascript
db.events.find({sessionId: UUID("<SID>"), type:"MESSAGE_SENT"}).sort({_id:1}).limit(1).forEach(e => print(e._id.toString()));
```

**액션**: 탭 A 페이지 새로고침 → **[Connect]** → `Last event ID` 입력란에 위 오래된 eventId **수동 입력** → **[Resume (lastEventId)]**

**raw frame**: STEP 6과 동일 (lastEventId만 옛 값)

**기대 응답**: `mode: "SNAPSHOT"` (INCREMENTAL 아님). catch-up 건수(~7) > threshold(2) 이므로.

**정리**
```bash
rm docker-compose.override.yml
docker compose up -d chat-server-1
```

---

### STEP 9 — Leave + 즉시 스냅샷 (§12.3 trigger 1)

**무엇**: 명시적 멤버십 종료. `participant_left` 영속 + presence 즉시 삭제 + 비동기 스냅샷 발화.

**액션**: 탭 A **[Leave]** 클릭

**raw frame**
```
SEND
destination:/app/sessions/{SID}/leave
content-type:application/json

{"userId":"user-1","clientEventId":"{CEID}"}
\0
```

**body 변수만**
```json
{"userId":"user-1","clientEventId":"33333333-3333-3333-3333-333333333333"}
```

**기대 결과**
- 탭 A: `[ACK] PARTICIPANT_LEFT`
- 탭 A·**B 모두**: `[TOPIC] PARTICIPANT_LEFT user-1` + `[TOPIC] presence OFFLINE user-1`

**검증 (Mongo)**
```javascript
db.events.find({sessionId: UUID("<SID>"), type: "PARTICIPANT_LEFT"}).pretty()
// 기대: 1건 (user-1)

// 1-2초 뒤 스냅샷 자동 생성 확인
db.snapshots.find({sessionId: UUID("<SID>")})
  .sort({snapshotAt:-1})
  .forEach(s => print(
    s._id.toString().substring(0,13),
    "upTo:", s.upToEventId.toString().substring(0,13),
    "msgs:", s.state.messages.length,
    "participants:", JSON.stringify(s.state.participants),
    "at:", s.snapshotAt
  ));
// 기대: 새 스냅샷 1건. participants에 user-2만 남음(leave 반영됨)
```

**검증 (Redis)**
```bash
docker exec realtime-redis redis-cli KEYS 'presence:*'
# 기대: presence:<SID>:user-2 만 남고 user-1은 사라짐
```

---

### STEP 10 — Disconnect (정상 종료)

**무엇**: STOMP DISCONNECT → WebSocket close. 인메모리 연결 테이블 정리만, **멤버십·presence는 건드리지 않음** (§8.5).

**액션**: 탭 A **[Disconnect]** (또는 탭 닫기)

**raw frame**
```
DISCONNECT
receipt:bye

\0
```

**기대 결과**
- 탭 A: `★ Disconnected`
- chat-server-1 로그: `STOMP disconnect: connection=...`
- 이 chat-server가 보유한 마지막 연결이면 Redis 채널 `UNSUBSCRIBE`

**검증 (Redis)**
```bash
docker exec realtime-redis redis-cli PUBSUB NUMSUB "channel:session:<SID>"
# 기대: 탭 A가 그 서버의 마지막 연결이었다면 NUMSUB 감소
```

**검증 (Mongo)**: events·sessions·snapshots 변동 없음 (멤버십 그대로).

---

## E. 자주 쓰는 Mongo 쿼리 모음 (참고)

```javascript
// 0. 진단: UUID가 STANDARD로 저장되는지 (UUID(...) 형태로 보여야 함, BinData(3,...) 면 LEGACY)
db.events.findOne()._id

// 1. 세션 1건의 모든 이벤트를 시간순으로
const sid = UUID("<SID>");
db.events.find({sessionId: sid}).sort({_id:1})
  .forEach(e => print(e._id.toString().substring(0,13), e.type.padEnd(20), e.actorUserId.padEnd(8), e.payload?.content || e.payload?.userId || ""));

// 2. 메시지만 시간순
db.events.find({sessionId: sid, type:"MESSAGE_SENT"})
  .sort({_id:1})
  .forEach(e => print(e._id.toString().substring(0,13), e.actorUserId.padEnd(8), e.payload.content));

// 3. lastEventId 이후 건수 (resume INCREMENTAL/SNAPSHOT 분기 검증)
const last = UUID("<LAST_EVENT_ID>");
db.events.countDocuments({sessionId: sid, _id: {$gt: last}});

// 4. 멱등 검증: 같은 clientEventId가 1건만 있는지
db.events.aggregate([
  {$match: {sessionId: sid}},
  {$group: {_id: "$clientEventId", n: {$sum:1}}},
  {$match: {n: {$gt:1}}}
]).toArray();
// 기대: 빈 배열

// 5. 스냅샷 목록
db.snapshots.find({sessionId: sid})
  .sort({snapshotAt:-1})
  .forEach(s => print(s._id.toString().substring(0,13), "upTo:", s.upToEventId.toString().substring(0,13), "msgs:", s.state.messages.length, "at:", s.snapshotAt));

// 6. 인덱스 확인
db.events.getIndexes().forEach(i => print(i.name, JSON.stringify(i.key)));

// 7. 세션 메타
db.sessions.find({_id: sid}).pretty();

// 8. 실시간 polling (1초마다 새 이벤트만)
let last_id = null;
const tick = () => {
  const q = last_id ? {_id: {$gt: last_id}} : {};
  const xs = db.events.find(q).sort({_id:1}).toArray();
  xs.forEach(e => print(new Date().toISOString(), e._id.toString().substring(0,13), e.type.padEnd(20), e.payload?.content || e.payload?.userId || ""));
  if (xs.length) last_id = xs[xs.length-1]._id;
};
const handle = setInterval(tick, 1000);
// 중단: clearInterval(handle)
```

---

## F. Redis 보조 쿼리

```bash
# 현재 presence 키 전체
docker exec realtime-redis redis-cli KEYS 'presence:*'

# 특정 presence TTL
docker exec realtime-redis redis-cli TTL "presence:<SID>:user-1"

# 활성 Pub/Sub 채널
docker exec realtime-redis redis-cli PUBSUB CHANNELS 'channel:session:*'

# 특정 채널 구독자 수 (chat-server-1·2 둘 다 구독하면 2)
docker exec realtime-redis redis-cli PUBSUB NUMSUB "channel:session:<SID>"

# Pub/Sub 흐름 실시간 (메시지 한 건 송신마다 한 줄)
docker exec realtime-redis redis-cli PSUBSCRIBE 'channel:session:*'

# 모든 redis 명령 실시간 모니터 (디버깅 시)
docker exec realtime-redis redis-cli MONITOR
```

---

## G. 한 번에 보는 흐름표

| STEP | 행위 | endpoint / destination | sessionId? | payload (body) | 응답 위치 | Mongo 변화 | Redis 변화 |
|---|---|---|---|---|---|---|---|
| 0 | 세션 생성 | `POST /sessions` (HTTP, 8080) | 응답으로 받음 | `{createdBy}` | HTTP 응답 | sessions +1, events SESSION_CREATED +1 | — |
| 1 | WS 연결 | `ws://localhost:8081/ws` | ❌ | — | CONNECTED frame | — | — |
| 2 | SUBSCRIBE × 3 | `/topic/sessions/{SID}`, `/user/queue/ack`, `/user/queue/resume` | ✅ topic만 | — | — | — | 첫 SUBSCRIBE면 chat-server가 redis `SUBSCRIBE channel:session:{SID}` |
| 3 | Join | `/app/sessions/{SID}/join` | ✅ | `{userId, clientEventId}` | `/topic`(전체), `/user/queue/ack`(본인) | events PARTICIPANT_JOINED +1 | `presence:{SID}:{userId}` SET EX 30 |
| 4 | 메시지 | `/app/sessions/{SID}/messages` (헤더 X-User-Id) | ✅ | `{clientEventId, content}` | 동일 | events MESSAGE_SENT +1 | publish (휘발성) |
| 5 | Heartbeat | `/app/sessions/{SID}/heartbeat` | ✅ | `{userId}` | — | — | TTL 갱신 |
| 6 | Resume | `/app/sessions/{SID}/resume` | ✅ | `{lastEventId}` | `/user/queue/resume` | — | — |
| 7 | Resume null | 동일 | ✅ | `{lastEventId:null}` | 동일 (mode:SNAPSHOT) | — | — |
| 8 | Resume stale | 동일 | ✅ | `{lastEventId:<오래된>}` | 동일 (임계치 초과 시 SNAPSHOT) | — | — |
| 9 | Leave | `/app/sessions/{SID}/leave` | ✅ | `{userId, clientEventId}` | `/topic`, `/user/queue/ack` | events PARTICIPANT_LEFT +1, snapshots +1 (비동기) | `presence:{SID}:{userId}` DEL |
| 10 | Disconnect | DISCONNECT frame | ❌ | — | — | — | 그 서버의 마지막 연결이었으면 channel UNSUBSCRIBE |

---

## H. 한 줄 요약

- **WebSocket 연결에 sessionId는 필요 없다.** ws://localhost:8081/ws만 있으면 됨.
- **sessionId는 STOMP SUBSCRIBE 부터** 등장한다. "어느 세션의 라이브를 받을지" + "어느 세션에 액션을 보낼지".
- **SUBSCRIBE는 연결의 일부가 아니라 메시지 받기 준비 단계.** Connect 다음, Send 이전에 한 번.
- **`X-User-Id`는 헤더** (메시지 송신용), **`userId`는 body** (join/leave/heartbeat용). 두 자리에 같이 두지 말 것.
