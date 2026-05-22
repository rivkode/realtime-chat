# API 명세

본 시스템의 API는 두 가지 — **REST**(세션 수명주기·복원·조회)와 **WebSocket(STOMP)**(실시간 메시지). 정식 REST 스펙은 [`openapi.yaml`](../../openapi.yaml)에 OpenAPI 3.0으로 작성한다. 이 문서는 개요와 STOMP 계약을 다룬다.

## 1. 통신 경계

| 경로 | 프로토콜 | 처리 주체 | 용도 |
|---|---|---|---|
| 세션 생성·종료·목록·복원 | HTTP REST | API 서버 (:8080) | 요청-응답 한 번으로 끝나는 일 |
| 실시간 메시지·join/leave·heartbeat·resume | WebSocket STOMP | 채팅 서버 (:8081, :8082) | 지속 연결 |

`POST /sessions/{id}/events`는 WebSocket과 동일한 멱등 수집 로직을 HTTP로도 노출해 테스트·재현을 쉽게 한다(둘 다 같은 `EventAppendService` 경로).

## 2. REST API 요약

공통 규칙 — 모든 ID는 UUID 문자열. 시각은 ISO-8601(UTC). 에러는 `{ "error": { "code", "message" } }`. 멱등이 필요한 쓰기 요청은 `clientEventId`를 본문에 포함.

| Method · Path | 용도 | 멱등 |
|---|---|---|
| `POST /sessions` | 세션 생성 | — |
| `POST /sessions/{id}/join` | 참여 | `clientEventId` |
| `POST /sessions/{id}/end` | 세션 종료 | `clientEventId` |
| `POST /sessions/{id}/events` | 이벤트/메시지 수집 | `clientEventId` |
| `GET /sessions` | 세션 목록 (status·participant·기간·cursor 필터) | — |
| `GET /sessions/{id}` | 세션 1건 | — |
| `GET /sessions/{id}/timeline?at=` | 특정 시점 상태 복원 | — |
| `GET /sessions/{id}/events?after=&limit=` | 이벤트 조회 (디버깅·Pull sync 검증) | — |
| `POST /sessions/{id}/snapshots` | 스냅샷 수동 트리거 (테스트용) | — |

### 멱등 응답 규약 (§9.1)

`POST /sessions/{id}/events`에 같은 `clientEventId`가 다시 오면 `{session_id, client_event_id}` unique index가 충돌을 감지한다. 서버는 **기존 이벤트를 조회해 그 `event_id`/`server_ts`를 `200 OK`로 반환**한다(신규는 `201 Created`). "무시"가 아니라 "이미 처리됨"을 알려 클라이언트가 분실로 오해하지 않게 한다.

### timeline 복원 (§10)

`GET /sessions/{id}/timeline?at=ISO-8601` — 가장 가까운 스냅샷에서 출발해 이후 이벤트를 결정론적 리듀서로 fold한 상태를 반환.

```json
{
  "sessionId": "...",
  "at": "2026-05-22T10:00:00Z",
  "participants": ["user-1", "user-2"],
  "messages": [
    { "eventId": "...", "sender": "user-1", "content": "안녕", "status": "SENT" }
  ],
  "sessionStatus": "ACTIVE"
}
```

`at`이 없으면 현재 시점. 같은 입력에 항상 같은 결과 — 리듀서가 순수 함수이기 때문(상세: [event-sourcing-restore.md](../reports/event-sourcing-restore.md)).

## 3. WebSocket (STOMP) 계약

엔드포인트: `ws://{chat-server}/ws`. STOMP 1.2.

| 방향 | destination | 페이로드 | 설명 |
|---|---|---|---|
| 클라이언트 → 서버 | `SEND /app/sessions/{id}/messages` | `{ clientEventId, content }` | 메시지 송신 |
| 클라이언트 → 서버 | `SEND /app/sessions/{id}/join` | `{ userId, clientEventId }` | 참여 |
| 클라이언트 → 서버 | `SEND /app/sessions/{id}/leave` | `{ userId, clientEventId }` | 퇴장 |
| 클라이언트 → 서버 | `SEND /app/sessions/{id}/heartbeat` | `{ userId }` | presence TTL 갱신 |
| 클라이언트 → 서버 | `SEND /app/sessions/{id}/resume` | `{ lastEventId }` | 재연결 시 누락분 요청 |
| 서버 → 클라이언트 | `SUBSCRIBE /topic/sessions/{id}` | 이벤트/presence 객체 | 해당 세션 라이브 이벤트 수신 |
| 서버 → 송신자 | `SEND /user/queue/ack` | `{ clientEventId, eventId, serverTs }` | 송신 ACK (멱등) |
| 서버 → 송신자 | `SEND /user/queue/resume` | resume 응답 | 재연결 catch-up 결과 |

채팅 서버는 `/topic/sessions/{id}` 구독을 받으면 내부적으로 Redis `channel:session:{id}`를 `SUBSCRIBE`하고, Redis에서 받은 이벤트를 그 STOMP destination으로 중계한다 — STOMP destination과 Redis 채널이 세션 단위로 1:1 대응한다.

헤더 `X-User-Id`(임시 사용자 식별), `X-Trace-Id`(추적, 없으면 서버 발급)를 SEND/CONNECT 헤더로 받는다.

STOMP frame 예시와 전체 시나리오는 [`sample-payloads.md`](../../sample-payloads.md) 참조.

## 4. 정식 OpenAPI 스펙

REST API의 스키마 컴포넌트·요청/응답 예시·전체 상태 코드는 [`openapi.yaml`](../../openapi.yaml)에 작성한다. Swagger UI 등으로 렌더링해 확인할 수 있다:

```bash
# 예: openapi.yaml을 Swagger UI로 보기
npx @redocly/cli preview-docs openapi.yaml
```
