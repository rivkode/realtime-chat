# 요구사항 분석

## 1. 과제 목적

동작하는 구현, 운영 환경에서의 설계 역량(정합성·확장성·장애 대응·관측 가능성), 백엔드 기본기(DB·쿼리·REST API)를 검증한다. 1:1 참여자 간 **실시간 채팅**과, 대화 중 발생한 이벤트를 기반으로 한 **특정 시점 상태 복원**(Event Sourcing)을 설계·구현한다.

## 2. 핵심 도메인

| 도메인 | 설명 |
|---|---|
| **Session** | 세션 — 시작/종료, 참여자, 상태(`ACTIVE` / `INTERRUPTED` / `ENDED`) |
| **Event / Message** | 이벤트 — `session_created`, `participant_joined`, `participant_left`, `message_sent`, `message_edited`, `message_deleted`, `session_ended` |
| **Snapshot** | 특정 시점의 세션 상태 복원을 위한 체크포인트 |

## 3. 중요 제약 (필수 고려)

### 3.1 중복 이벤트 발생 가능
네트워크 불안정으로 클라이언트가 동일 메시지/이벤트를 재전송할 수 있다. 서버는 중복 저장·중복 반영을 최소화해야 한다.

→ **본 구현의 처리**: 클라이언트가 이벤트마다 `client_event_id`(UUID)를 발급하고, MongoDB `{session_id, client_event_id}` unique index가 두 번째 INSERT를 duplicate key error로 차단한다. 충돌 시 서버는 기존 이벤트를 조회해 그 `event_id`/`server_ts`를 ACK로 응답한다 — "무시했다"가 아니라 "이미 처리됐고 결과는 이것"임을 알려 클라이언트가 분실로 오해하지 않게 한다. 상세: [중복 처리 & 순서 보장](../reports/idempotency-ordering.md).

### 3.2 순서 뒤바뀜 발생 가능
메시지/이벤트 도착 순서가 뒤바뀔 수 있다. 일관된 기준을 정의해야 한다.

→ **본 구현의 처리**: 정렬의 정본은 **UUIDv7 이벤트 `_id`**(서버 수신 순서)다. 세 가지 시각(`client_ts`·`server_ts`·`_id`)을 저장하되, 조회·복원은 항상 `sort({_id: 1})`로 읽어 도착 순서와 무관하게 일관된 결과를 얻는다. 상세: [중복 처리 & 순서 보장](../reports/idempotency-ordering.md).

## 4. 요구사항별 구현 현황

### 4.1 필수 구현 (실제 동작)

| 요구사항 | 상태 | 위치 |
|---|---|---|
| 실시간 기반 메시지 송수신 | ✅ 구현 | WebSocket(STOMP) — `chat-server` |
| 사용자 join / leave 처리 | ✅ 구현 | `participant_joined` / `participant_left` 이벤트 |
| 기본 presence 처리 | ✅ 구현 | heartbeat 기반 Redis TTL — 세션 참여/접속 상태 |
| 이벤트/메시지 수집 API | ✅ 구현 | STOMP `SEND` + REST `POST /sessions/{id}/events` |
| 중복 이벤트 방지 전략 | ✅ 구현 | `client_event_id` + unique index |
| 순서 뒤바뀜 처리 기준 | ✅ 구현 | UUIDv7 `_id` 정렬 |
| 특정 시점 세션 상태 복원 | ✅ 구현 | `GET /sessions/{id}/timeline?at=` |

### 4.2 필수 설계/문서

| 항목 | 상태 | 위치 |
|---|---|---|
| DB 설계 (스키마·인덱스·근거) | ✅ 문서 + 구현 | [data-model.md](data-model.md) |
| REST API 설계 (OpenAPI) | ✅ 문서 + 구현 | [`openapi.yaml`](../../openapi.yaml) |
| 재연결 정합성 | ✅ 문서 + 구현 | [reconnect-recovery.md](../reports/reconnect-recovery.md) |
| 수평 확장 전략 | ✅ 문서 + 구현 | [scalability.md](../reports/scalability.md) |
| 관측 가능성 | ✅ 문서 + 구현 | [observability.md](../reports/observability.md) |
| 비동기 처리 구조 | ✅ 문서 | [async-processing.md](../reports/async-processing.md) |
| 장애 대응 시나리오 | ✅ 문서 + 구현 | [fault-tolerance.md](../reports/fault-tolerance.md) |

### 4.3 이벤트 기반 상태 복원

✅ **구현 완료** — 스냅샷 + 결정론적 리플레이. 상세: [event-sourcing-restore.md](../reports/event-sourcing-restore.md).

### 4.4 가산점 항목

| 항목 | 상태 |
|---|---|
| Snapshot 생성 자동화 | ✅ 구현 — leave/end 즉시 트리거 + `@Scheduled` 배치 |
| 부하 테스트 및 성능 측정 | ✅ 구현 — [load-test.md](../reports/load-test.md) |
| 테스트 전략 고도화 | ✅ 구현 — 자동 E2E 18개 + 장애 주입(Redis·채팅서버 stop/start) + 결정론 검증 |
| 운영 메트릭 | ✅ 구현 — Prometheus endpoint, 단계별 카운터 |

## 5. 범위와 비목표

### 범위
1:1 참여자 간 실시간 채팅, 이벤트 기반 특정 시점 상태 복원.

### 비목표 (Non-goals)
- 프론트엔드·모바일 UI (동작 검증은 E2E 스크립트·테스트 클라이언트로 대체)
- 운영 배포 자동화·인프라 완성도
- 인증/인가 체계의 완결된 구현 (단순 토큰 가정 — `X-User-Id` 헤더)
- 대형 그룹 채팅

## 6. 명시한 가정

설계서에 명시 없는 결정은 가정을 명기하고 진행했다. 주요 가정:

- `sessions` 도큐먼트에 `participants` 필드를 두지 않고, 응답 시 `events`의 `participant_joined`에서 집계한다 — 별도 읽기 모델을 두지 않는 원칙(D1)을 유지. 1:1이라 경량.
- `POST /sessions`는 `created_by`를 자동으로 참여시키지 않는다 — join은 명시적 호출.
- `session_created` 이벤트의 `client_event_id`는 `session_id` 자체를 사용한다 — 세션 ID가 곧 유일성 보장.
- 인증 전 임시 사용자 식별은 STOMP/REST 헤더 `X-User-Id`로 가정한다.
