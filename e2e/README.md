# E2E — Functional 시나리오 18개 + 부하 테스트

설계서의 핵심 기능을 자동 검증하는 Node.js 기반 테스트. functional(`scenario.js`)과 부하(`load.js`) 두 모드.

## Functional 검증 매트릭스 (18개)

| # | 시나리오 | 설계서 | 검증 방법 |
|---|---|---|---|
| 1 | 세션 생성 (POST /sessions) | §16 | `sessions` + SESSION_CREATED event INSERT |
| 2 | WS 연결 + SUBSCRIBE × 3 (양 서버) | §5·§8.4 | A@8081, B@8082 STOMP CONNECTED |
| 3 | Join × 2 + 양쪽 라이브 전달 | §5.3·§8.2 | events PARTICIPANT_JOINED ×2, 상대 탭에 도착 |
| 4 | 메시지 20건 양방향 + 시간순 정렬 | §8.1·§9.2 | events MESSAGE_SENT ×20, `_id` ASC 와 송신 순서 일치 |
| 5 | 멱등 (같은 clientEventId × 2) | §9.1 | events INSERT 1, ACK 2 (같은 eventId) |
| 6 | Edit (MESSAGE_EDITED) | §6.2 + §10.2 | timeline content 갱신, status=EDITED |
| 7 | Delete (MESSAGE_DELETED) | §6.2 + §10.2 | timeline status=DELETED (soft-delete) |
| 8 | Resume INCREMENTAL | §9.3 | mode=INCREMENTAL, lastEventId 이후 events |
| 9 | Resume SNAPSHOT (null) | §9.3 | mode=SNAPSHOT, state 시간순 |
| 10 | Timeline at=과거시점 | §10 | 그 시점까지 메시지만 |
| 11 | Leave + 즉시 스냅샷 | §8.2 + §12.3 trigger 1 | PARTICIPANT_LEFT event + snapshots +1 |
| 12 | 결정론 (스냅샷 ≡ 같은 시점 timeline) | §10 | 두 결과 메시지 집합 동일 |
| 13 | Actuator /health | §15.1 | 3개 서버 UP + mongo/redis indicator UP |
| 14 | Prometheus 메트릭 | §14.3 | 단계별 카운터 + 처리 지연 Timer + 활성 Gauge |
| 15 | traceId 전파 | §14.2 | STOMP 매건 다른 UUID + REST 헤더 echo + events 박힘 |
| 16 | Redis stop — graceful degradation | §15.4 | events INSERT 계속 + ACK 정상 |
| 17 | Redis recovery — self-healing | §15.4 | 재구독 후 라이브 전달 정상 |
| 18 | 채팅 서버 다운 → 재연결 → resume catch-up | §15.1 | chat-server-1 stop → 다른 서버 재연결 → 다운 중 누락분 resume 복구 |

## 부하 테스트 (`load.js`, 설계서 §18)

- user-1·user-2가 각각 5초간 50ms 간격 100건 → 한 세션 ~200 이벤트
- 측정: client send→ACK 지연(p50/p95/p99), 서버측 처리 지연(Prometheus), UUIDv7 순서 정합성
- `timeline?at=` 복원 시간을 누적 건수별로 측정 → `SNAPSHOT_THRESHOLD` 권장값 도출

## 실행

```bash
# functional 시나리오
./e2e/run.sh                      # 컨테이너 떠 있을 때
./e2e/run.sh --rebuild            # 깨끗한 상태로 재빌드부터
./e2e/run.sh --restart            # 데이터만 청소 (재빌드 없이)

# 부하 테스트
./e2e/run.sh --load               # 부하 모드 (load.js)
./e2e/run.sh --rebuild --load     # 재빌드 후 부하
```

처음 실행 시 `npm install` 자동 수행 (~10초). 이후 실행은 `node_modules` 캐시.

## 부하 테스트 환경 변수

| 변수 | 기본값 | 의미 |
|---|---|---|
| `LOAD_BURST_PER_USER` | `100` | 유저당 송신 건수 |
| `LOAD_BURST_INTERVAL_MS` | `50` | 송신 간격 |
| `LOAD_RESTORE_STEPS` | `2000,5000,10000,20000` | 복원 시간 측정 누적 단계 |
| `LOAD_RESTORE_TARGET_MS` | `1000` | 복원 1초 임계치 |
| `LOAD_P99_TARGET_MS` | `200` | client p99 PASS 기준 |

## 환경 변수 (기본값)

| 변수 | 기본값 | 의미 |
|---|---|---|
| `API_URL` | `http://localhost:8080` | api-server REST |
| `CHAT1_URL` | `ws://localhost:8081/ws` | chat-server-1 STOMP |
| `CHAT2_URL` | `ws://localhost:8082/ws` | chat-server-2 STOMP |
| `MONGO_URI` | `mongodb://localhost:27017/realtime` | Mongo 검증용 (Node driver는 BSON UUID class로 자동 STANDARD) |

## 출력

```
[01] 세션 생성 (POST /sessions) ......................... PASS
[02] WS 연결 + SUBSCRIBE × 3 (양 서버) ................... PASS
... (12개)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   요약: 12개 시나리오 · 12 PASS · 0 FAIL
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

PASS = exit 0, 하나라도 FAIL = exit 1, 시스템 오류(인프라 다운 등) = exit 2.

## 어디서 실패하는지 진단

각 라인의 우측에 핵심 수치가 그대로 노출 (`events=2/2`, `inserts=1, acks=2` 등). FAIL이면 끝에 실패 목록만 다시 출력.

추가 진단:
```bash
# events 시간순
mongosh "mongodb://localhost:27017/realtime?uuidRepresentation=STANDARD" --eval '
  db.events.find().sort({_id:1}).forEach(e => print(e._id.toString().substring(0,13), e.type.padEnd(20), e.payload?.content || e.payload?.userId || ""));
'

# snapshots 확인
mongosh "mongodb://localhost:27017/realtime?uuidRepresentation=STANDARD" --eval '
  db.snapshots.find().sort({snapshotAt:-1}).forEach(s => print(s._id.toString().substring(0,13), "upTo:", s.upToEventId.toString().substring(0,13), "msgs:", s.state.messages.length));
'

# Redis presence
docker exec realtime-redis redis-cli KEYS 'presence:*'
```

자세한 페이로드·STOMP frame은 프로젝트 루트 `sample-payloads.md` 참조.

## 도구 요구사항

- Docker 28+ / Docker Compose v2
- Node.js 18+ (Node 20+ 권장 — Node 18은 `fetch` 도입 시점)
- macOS 또는 Linux (run.sh는 bash)

## 의존성 (npm)

| 패키지 | 용도 |
|---|---|
| `@stomp/stompjs` | STOMP 1.2 클라이언트 |
| `ws` | Node용 WebSocket polyfill (stompjs가 사용) |
| `uuid` | clientEventId 발급 |
| `mongodb` | 검증용 Mongo driver |

## 확장

새 시나리오를 추가하려면 `scenario.js`의 `try` 블록 끝에 항목을 추가하고 `record(name, pass, detail)` 호출. 검증은 mongo 쿼리·STOMP 응답 양쪽으로 가능.
