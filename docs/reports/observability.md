# 관측 가능성

로그·추적·메트릭 세 축 모두 "항목 나열"이 아니라 "어떻게 측정·전파하는가"를 설계한다.

## 1. 구조화 로그 + MDC

구조화 JSON 로그를 쓰고, 모든 라인에 `session_id`·`event_id`·`user_id`·`trace_id`를 포함한다. 공통 필드를 **MDC(Mapped Diagnostic Context)**에 한 번 넣어두면 그 요청 범위의 모든 로그에 자동으로 붙는다. 이로써 특정 세션·특정 메시지에 얽힌 로그를 한 번에 필터링할 수 있다.

구현:
- `logback-spring.xml` + `logstash-logback-encoder` — MDC 키를 JSON 최상위 필드로 노출
- `RequestMdcFilter` (api-server) — HTTP 요청마다 `requestId`·`traceId`·`sessionId`를 MDC에
- `StompMdcChannelInterceptor` (chat-server) — STOMP 메시지마다 `connectionId`·`sessionId`·`userId`·`traceId`를 MDC에

```json
{"ts":"2026-05-22T...","msg":"...","level":"INFO","service":"chat-server",
 "instance":"chat-server-1","sessionId":"019e...","userId":"user-1","traceId":"0799..."}
```

## 2. 추적 — trace_id의 발급과 전파

"메시지 1건의 경로를 추적한다"는 것은 `trace_id` 하나가 전 구간을 관통한다는 뜻이다. 핵심은 **누가 발급하고 어떻게 전파하느냐**다.

- **발급**: 메시지를 처음 받는 서버가 `trace_id`를 발급한다(클라이언트가 `X-Trace-Id`를 보내면 그것을 잇는다).
- **전파**: 이 `trace_id`가 경로 전체를 따라가야 한다.
  1. `events` 도큐먼트에 `traceId` 필드로 저장 — MongoDB 단계가 추적에 포함된다.
  2. Redis publish 시 메시지 페이로드에 `traceId`를 함께 실어 보낸다 — Redis Pub/Sub은 헤더 개념이 없으므로 페이로드에 넣는 것이 유일한 전파 수단이다.
  3. 수신 측 채팅 서버는 페이로드에서 `traceId`를 꺼내 자기 MDC에 넣는다 — 상대 WebSocket push 단계까지 같은 ID로 이어진다.

경로: `trace_id 발급(WS 수신) → events INSERT → ACK → Redis publish → 수신 서버가 추출 → 상대 WS push`. 두 채팅 서버가 서로 다른 인스턴스여도 `trace_id`가 페이로드를 타고 넘어가므로 단일 추적으로 묶인다.

> **구현 함정** — Spring `ExecutorSubscribableChannel`은 `ChannelInterceptor.preSend`를 calling thread에서, 핸들러를 executor thread에서 실행한다. `preSend`에서 채운 MDC가 핸들러 thread에 보이지 않는다. `ExecutorChannelInterceptor`의 `beforeHandle`/`afterMessageHandled`(핸들러 thread에서 호출)로 바꿔 해결했다.

사용:
```bash
docker compose logs chat-server-1 chat-server-2 api-server | grep '"traceId":"0799..."'
```
→ 그 한 메시지의 수신 → INSERT → publish → 수신 측 push 모든 로그가 한 줄로 흘러나온다.

## 3. 메트릭과 단계별 카운터

Micrometer + `/actuator/prometheus`로 노출한다.

| 메트릭 | 측정 지점 | 타입 |
|---|---|---|
| 메시지 처리 지연 | WS 수신 ~ ACK 응답 | `chat.message.dispatch` Timer |
| 활성 WebSocket 연결 수 | 채팅 서버 인메모리 연결 테이블 크기 | `chat.websocket.active.sessions` Gauge |
| 단계별 카운터 | 아래 | Counter |

### 단계별 카운터 — received / persisted / published / delivered

`received / persisted / published / delivered` 네 카운터로 유실 지점을 진단한다. 각 카운터가 어디서 올라가는지 정확히 해야 의미가 있다.

- `received`, `persisted`, `published`는 **발신 측 채팅 서버**에서 순서대로 올린다 — WS 수신, `events` INSERT 성공, Redis publish 성공 시점. 이 셋의 차이로 "저장 전 유실"인지 "발행 전 유실"인지 가른다.
- `delivered`는 **수신 측 채팅 서버**가 상대 WebSocket push에 성공할 때 올린다. Redis Pub/Sub은 수신 확인이 없으므로 발신 측은 `delivered`를 알 수 없다 — 이 카운터는 발신 측과 다른 서버·다른 시점에 집계된다.

따라서 `published`와 `delivered`의 직접 비교로 개별 메시지의 유실을 단정할 수는 없다(서로 다른 서버의 집계라 시점이 어긋남). 대신 (1) 전체 집계 수준에서 `published` 합과 `delivered` 합의 추세가 크게 벌어지면 라이브 전달 경로의 이상으로 보고, (2) 개별 메시지의 끝까지 추적은 `trace_id`로 한다. **카운터는 "어느 구간이 이상한가"를 가리키고, trace_id는 "이 한 건이 어디서 멈췄나"를 답한다** — 둘은 역할이 다르며 함께 써야 진단이 완성된다.

> 라이브 전달은 best-effort이므로 `published`와 `delivered`의 차이 자체가 곧 장애는 아니다(수신자가 잠깐 끊겼을 수 있음). 이 차이는 "조사 트리거"이지 "유실 확정"이 아니다.

## 4. 헬스체크

Spring Boot Actuator `/actuator/health` — Mongo·Redis indicator가 자동 등록되어 health JSON에 노출된다. Resilience4j circuit breaker 상태도 포함된다. 로드 밸런서 헬스체크의 진입점이며, `liveness`/`readiness` probe도 노출한다([장애 대응](fault-tolerance.md)).

## 검증

- E2E 시나리오 [13] — 3개 서버 `/health` UP + mongo/redis indicator UP.
- E2E 시나리오 [14] — 단계별 카운터 4종 + 처리 지연 Timer + 활성 Gauge 노출.
- E2E 시나리오 [15] — STOMP 매 메시지가 다른 UUID `traceId`로 events에 박힘, REST `X-Trace-Id` 헤더 echo.

## 구현 PR

PR #9 (JSON 로그 + MDC + Micrometer), PR #10 (trace_id 발급/전파).
