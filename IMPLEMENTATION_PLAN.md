# 실시간 채팅 시스템 — 요약과 구현 작업 분해

## Context

`chat-system-architecture.md` 설계서 전체(910라인)를 통독한 뒤, 시스템을 한 문단으로 요약하고 향후 구현을 어떤 기능 단위로 쪼개면 좋을지 정리한다. 코드 작성은 아직 하지 않는다.

---

## 한 문단 요약

이 시스템은 **MongoDB `events` 컬렉션 하나를 진실의 원천**으로 두는 이벤트 소싱 기반 1:1 실시간 채팅이다. 무상태 **API 서버**(세션 CRUD·timeline 복원·스냅샷 스케줄러)와 상태 유지형 **채팅 서버**(WebSocket/STOMP·이벤트 수집·presence)로 계층을 나누고, 외부 시스템은 **MongoDB**(영속·샤딩)와 **Redis**(Pub/Sub 채널·presence TTL) 둘뿐이다. 이벤트 수집은 UUIDv7을 `_id`로 박은 **단일 도큐먼트 INSERT**라 멀티 도큐먼트 트랜잭션·큐·CDC가 필요 없고, `client_event_id` unique index가 중복을 흡수한다. 라이브 전달은 **세션 단위 Redis Pub/Sub 채널(`channel:session:{id}`)**로 수신자가 어느 채팅 서버에 붙어 있는지 모르고도 중계되며, 전달 유실은 서버가 추적하지 않고 **수신 측 Pull(재연결 resume + 주기적 sync)**로 메운다. 과거 시점 복원은 **스냅샷 + 결정론적 리듀서 리플레이**로 비용을 상한 짓고, 스냅샷은 leave/end 즉시 + `@Scheduled` 배치(임계치 N) 두 트리거로 채운다. presence는 heartbeat 기반 Redis TTL로 다루며 정합성에 관여하지 않는 순수 표시용이다.

---

## 구현 작업 분해 (기능 단위)

각 항목 옆 §는 설계서의 해당 절. 굵게 표시된 항목은 다른 모듈/기능의 전제가 되는 **선행 작업**.

### Phase A — 기반 구축 (선행)

1. **멀티모듈 / 인프라 셋업** §4.2
   - Gradle 멀티모듈: `api-server`, `chat-server`, `common`(공유 도메인·DTO·이벤트 정의)
   - Docker Compose: `api-server` ×1, `chat-server-1`, `chat-server-2`, `mongodb`, `redis`
   - 로컬 환경에서 채팅 서버 2대를 동시에 띄울 수 있게 구성(§5.3 검증 전제)

2. **공통 도메인 모델 (`common`)** §6.2 §7.2
   - 이벤트 카탈로그: `session_created` / `participant_joined`·`_left` / `message_sent`·`_edited`·`_deleted` / `session_ended`
   - 도큐먼트 스키마: `Event`, `Session`, `Snapshot`
   - WebSocket/REST DTO, 직렬화(JSON)

3. **MongoDB 인프라 / 인덱스 / UUIDv7** §7.1 §7.3 §2.3
   - Spring Data MongoDB 설정, 컬렉션 3개 부트스트랩
   - 인덱스 5종 생성(`{session_id,_id}`, `{session_id,client_event_id} unique`, `{session_id,type,_id}`, `{session_id,server_ts}`, `snapshots {session_id, up_to_event_id desc}`)
   - UUIDv7 생성 유틸 (서버 인메모리)
   - write concern `majority` 설정 §15.3

### Phase B — API 서버 (HTTP 경로)

4. **세션 수명주기 REST API** §16
   - `POST /sessions`, `POST /sessions/{id}/join`, `POST /sessions/{id}/end`, `GET /sessions`
   - join은 멱등(이미 active면 기존 상태 반환) §8.2

5. **이벤트 수집 REST 엔드포인트 (WS와 동일 로직 노출)** §16
   - `POST /sessions/{id}/events` — `client_event_id` 멱등 처리, duplicate key 시 기존 event 반환
   - `GET /sessions/{id}/events?from=&to=` — 검증·디버깅용

6. **시점 복원 (timeline)** §10
   - `GET /sessions/{id}/timeline?at=` (또는 `at_event_id`)
   - 가장 가까운 스냅샷 조회 → 이후 이벤트 `sort({_id:1})` 리플레이 → 순수 함수 리듀서 fold
   - `target_event_id` no-op 방어 §10.3

7. **스냅샷 스케줄러** §12.3
   - 이벤트 트리거: `participant_left` / `session_ended` 발생 시 `@Async`로 즉시 스냅샷
   - 배치 트리거: `@Scheduled(1h)` — "마지막 스냅샷 이후 이벤트 수 > N(잠정 500)"인 세션만 대상
   - 멱등성: `snapshots {session_id, up_to_event_id} unique`
   - 수동 트리거 `POST /sessions/{id}/snapshots`

### Phase C — 채팅 서버 (WebSocket 경로)

8. **WebSocket / STOMP 기반 셋업** §5 §8.4
   - STOMP destination 매핑: `SEND /app/sessions/{id}/messages`, `SUBSCRIBE /topic/sessions/{id}`, `SEND /app/sessions/{id}/resume`, `SEND /user/queue/ack`
   - STOMP 프로토콜 heartbeat 활성화

9. **인메모리 연결 테이블 + 수명주기 핸들러** §8.5
   - `ConcurrentHashMap<sessionId, Set<WebSocketSession>>` + 역방향 `connectionId → sessionId`
   - Spring `SessionConnectedEvent` / `SessionDisconnectEvent` `@EventListener`
   - DISCONNECT는 인메모리·구독 정리만, 멤버십·presence는 건드리지 않음

10. **메시지 송신 (이벤트 수집 핫패스)** §8.1 §9.1
    - WS 프레임 수신 → UUIDv7 발급 → `events` INSERT → ACK
    - 중복 시 기존 event_id/server_ts로 멱등 ACK
    - Redis publish는 저장 성공 후

11. **Redis Pub/Sub 라이브 전달** §5.3
    - 세션의 첫 연결 시 `SUBSCRIBE channel:session:{id}`, 마지막 끊김 시 `UNSUBSCRIBE`
    - 발신 측: `PUBLISH channel:session:{id}` (저장 후 best-effort)
    - 수신 측: Redis listener → 인메모리 테이블 lookup → 해당 WS push

12. **join / leave 흐름** §8.2
    - `participant_joined` / `participant_left` 이벤트화, 같은 채널로 전파
    - join 멱등(이미 active면 새 이벤트 생성 안 함)

13. **presence (heartbeat 기반)** §8.3
    - 키 `presence:{session_id}:{user_id}` `SET EX 30`
    - 능동 전파: 접속/명시적 leave 시 세션 채널로 publish (A: SET + B: publish 별개)
    - 수동 인지: 비정상 단절은 TTL 만료 + 상대 폴링/재진입 시 키 부재로 판정

### Phase D — 정합성 / 복구

14. **재연결 resume (Pull 복구)** §9.3
    - `SEND /app/sessions/{id}/resume {last_event_id}`
    - **순서**: ① 채널 SUBSCRIBE → 라이브 이벤트 버퍼링 ② catch-up `_id > last_event_id` 조회·전송 ③ 버퍼 중 catch-up 마지막 `_id` 초과분만 합류 ④ 라이브 전환
    - 임계치 초과 또는 `last_event_id` 부재 시 스냅샷 기반 초기 로드로 우회

15. **주기적 sync** §9.3
    - 클라이언트가 heartbeat 또는 30s~1m 주기로 `last_event_id` 전송 → 차분 응답
    - resume과 같은 `_id > last_event_id` 조회 경로 재사용

### Phase E — 운영성

16. **장애 대응 / Graceful Degradation** §15
    - Redis: try-catch + Resilience4j circuit breaker, 실패해도 저장·ACK 정상 진행
    - MongoDB: `waitQueueTimeoutMS`, circuit breaker, write concern majority
    - 헬스체크 엔드포인트 (로드 밸런서용)
    - Redis 복구 후 인메모리 연결 테이블 기반 자동 재구독(self-healing)

17. **관측 가능성 — 로그·trace·메트릭** §14
    - 구조화 JSON 로그 + MDC(`session_id`, `event_id`, `user_id`, `trace_id`)
    - trace_id 발급(WS 수신 시) → `events` 도큐먼트 필드 → Redis publish 페이로드 동봉 → 수신 서버가 MDC에 복원
    - 카운터: `received` / `persisted` / `published`(발신측) / `delivered`(수신측)
    - 메트릭: 처리 지연 p50/p95/p99, 활성 WS 연결 수, Pub/Sub 발행 처리량, MongoDB 쓰기 지연

18. **부하 테스트 도구·시나리오** §18
    - WebSocket 테스트 클라이언트 (CLI)
    - 시나리오: 유저1 5초간 50ms 간격 100개 → 유저2 동일 패턴 100개 (한 세션 ~200 이벤트)
    - 측정: 처리 지연, 순서 정합성, **복원 시간 1초 임계치로 스냅샷 N 확정**, 다중 채팅 서버 라이브 전달

---

## 권장 진행 순서

A (1→2→3) → B (4→5→6→7과 C는 병렬 가능) → C (8→9→10→11→12→13) → D (14→15) → E (16→17→18).

핵심 의존성:
- **3(MongoDB/인덱스/UUIDv7)** 이 거의 모든 후속 작업의 전제
- **8~11(WS·연결테이블·메시지·Pub/Sub)** 이 묶음으로 함께 동작해야 §5.3의 "서로 다른 서버 두 유저" 시나리오 검증 가능
- **14(resume)** 는 **9·11**이 완성된 뒤
- **18(부하 테스트)** 은 7(스냅샷 임계치 N 측정)과 17(메트릭) 의 검증 도구이므로 거의 마지막

---

## 검증 (End-to-End)

- Docker Compose로 전체 스택 기동 → 채팅 서버 2대(`chat-server-1`, `-2`)에 각각 유저 1·2 접속 → 1:1 메시지 송수신
- 한 채팅 서버를 죽이고 재연결 시 resume이 누락 메시지를 메우는지 확인 §15.1
- Redis를 잠시 내리고 graceful degradation + 복구 후 self-healing 확인 §15.4
- `GET /sessions/{id}/timeline?at=` 로 과거 시점 복원이 결정론적으로 같은 결과를 주는지 확인 §10
- 부하 시나리오 §18 로 복원 1초 임계치 측정 → 스냅샷 N 확정
