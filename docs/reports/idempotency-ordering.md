# 중복 처리 & 순서 보장

과제 명세 §2의 두 필수 제약 — 중복 이벤트 발생 가능, 순서 뒤바뀜 발생 가능 — 에 대한 처리.

## 1. 중복 이벤트 (§2.1)

### 문제

네트워크 불안정으로 클라이언트가 동일 메시지/이벤트를 재전송할 수 있다. 서버는 중복 저장·중복 반영을 최소화해야 한다.

### 접근 — client_event_id + MongoDB unique index

클라이언트는 이벤트마다 `client_event_id`(UUID)를 발급한다. 서버는 `events`에 `{session_id, client_event_id}` **unique index**를 걸고, 재전송으로 같은 ID가 와도 두 번째 INSERT는 duplicate key error로 막힌다.

**충돌 시 서버는 원래 이벤트를 조회해 그 `event_id`/`server_ts`를 ACK로 응답한다.** "무시했다"가 아니라 "이미 처리됐고 결과는 이것"이라고 알려줘야 클라이언트가 메시지 분실로 오해하지 않는다. ACK 자체가 멱등하다. 권위는 전적으로 MongoDB unique index에 있다.

구현: `common.application.EventAppendService.append()`.

```java
try {
  eventRepository.append(event);          // INSERT
  return new AppendResult(event, false);  // 신규
} catch (DuplicateClientEventIdException ex) {
  Event existing = eventRepository.findByClientEventId(sessionId, clientEventId)
      .orElseThrow(...);
  return new AppendResult(existing, true);  // 멱등 — 기존 이벤트 반환
}
```

### 왜 애플리케이션 레벨 중복 체크가 아닌가

"INSERT 전에 SELECT로 존재 확인"은 **TOCTOU 경쟁**이 있다 — 두 재전송이 동시에 SELECT를 통과한 뒤 둘 다 INSERT할 수 있다. unique index는 DB가 원자적으로 보장하므로 경쟁이 없다. 같은 세션에 동시 INSERT가 몰려도 안전하다.

### REST·WebSocket 공통 경로

`POST /sessions/{id}/events`(REST)와 STOMP `SEND`(WebSocket) 두 입구가 같은 `EventAppendService`를 호출한다. 입구가 달라도 멱등 보장이 동일하게 적용된다.

## 2. 순서 뒤바뀜 (§2.2)

### 문제

메시지/이벤트 도착 순서가 뒤바뀔 수 있다. 일관된 정렬 기준을 정의해야 한다.

### 접근 — UUIDv7 이벤트 _id가 정렬의 정본

세 가지 시각을 저장하되 **정렬의 정본은 UUIDv7 이벤트 `_id`**다.

| 값 | 출처 | 용도 |
|---|---|---|
| `clientTs` | 클라이언트 | 참고용. 신뢰하지 않음 (클럭 스큐·조작 가능) |
| `serverTs` | 서버 수신 시각 | `timeline?at=` 시간 필터 |
| `_id` (UUIDv7) | 서버, 메시지 수신 시 발급 | **정렬·복원·재연결 커서의 기준** |

이벤트가 어떤 순서로 도착하든 저장 시 UUIDv7이 박히고, 조회·복원은 항상 `sort({_id: 1})`로 읽어 일관된 순서를 얻는다.

### 왜 UUIDv7인가

UUIDv7은 앞 48bit가 unix 밀리초 타임스탬프 — **ID 자체로 시간순 정렬**이 된다. 별도 `seq` 컬럼이 필요 없다:

| 검토안 | 비용 |
|---|---|
| 세션 내 `seq` 정수 | 동시 INSERT 시 race condition. 채팅 서버 여러 대면 분산 카운터 필요 — 또 하나의 단일 장애점 |
| 전역 `seq` 시퀀스 | 카운터에 모든 쓰기 직렬화 → 병목 |
| **UUIDv7 (채택)** | 각 서버가 인메모리에서 발급. 인스턴스 간 조율 불필요. 단일 필드가 PK·멱등 식별·시간 정렬을 모두 충족 |

구현은 [java-uuid-generator(JUG)](https://github.com/cowtowncoder/java-uuid-generator)의 `timeBasedEpochGenerator()` — monotonic counter로 **같은 ms 안에서도 단조 증가**를 보장한다.

### MongoDB 저장 시 함정 — uuidRepresentation

UUID를 BSON에 저장할 때 byte order가 정렬을 좌우한다.

| representation | byte 0..15 | 정렬 결과 |
|---|---|---|
| **STANDARD** (채택) | UUIDv7 layout 그대로 — 앞 6byte가 timestamp | byte-by-byte 비교 = 시간순 ✓ |
| JAVA_LEGACY (디폴트일 수 있음) | msb/lsb 각각 reverse | timestamp가 뒤로 밀려 random처럼 정렬 ✗ |

`spring.data.mongodb.uri`에 `?uuidRepresentation=STANDARD`를 명시해 이 함정을 차단했다. (실제로 개발 중 LEGACY로 저장돼 `timeline` 응답의 메시지 순서가 뒤죽박죽 나온 버그를 겪고 수정한 부분이다.)

### 한계와 트레이드오프 (명시)

UUIDv7은 "서버가 ID를 발급한 순서" = **서버 수신 순서**를 정렬한다. 클라이언트가 메시지를 *만든* 순서가 아니다. 1:1 채팅에서 같은 사용자가 1ms 내에 두 메시지를 보내 순서가 뒤집힐 가능성은 무시할 수준이라 서버 수신 순서를 일관 기준으로 채택했다. 클라이언트 의도 순서까지 보장하려면 `client_seq`(1,2,3…)를 보조 정렬 키로 추가하면 된다(향후 확장).

## 검증

- E2E 시나리오 [4] — 메시지 20건 송신 후 `_id` ASC 정렬이 송신 순서와 일치.
- E2E 시나리오 [5] — 같은 `clientEventId`로 두 번 송신 → events INSERT 1건, ACK 2건(같은 `eventId`).
- 부하 테스트 — 50ms 간격 200건 전수 순서 일치([load-test.md](load-test.md)).

## 구현 PR

PR #1 (`EventAppendService` + unique index), UUIDv7 JUG 교체 커밋, UUID STANDARD 수정 커밋.
