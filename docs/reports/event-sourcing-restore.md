# 이벤트 소싱 & 상태 복원

## 문제

대화 중 발생한 모든 이벤트를 기반으로 **특정 시점 `t`의 대화 상태를 복원**해야 한다. 복원 대상은 그 시점의 참여자 목록, 메시지 목록, 각 메시지의 상태(전송/수정/삭제). 핵심 검증 포인트는 **결정론(Determinism)** — 같은 이벤트 집합이면 항상 같은 상태가 나와야 한다.

## 접근 — 이벤트 소싱

상태를 저장하는 대신 **상태를 만들어낸 사건(event)들의 불변 시퀀스**를 저장한다. 현재 상태는 이벤트를 순서대로 적용(fold)해 계산하고, 일부 이벤트까지만 적용하면 과거 시점의 상태가 된다 — 이것이 "특정 시점 복원"의 메커니즘이다.

`events` 컬렉션은 **append-only**다. INSERT만 하고 도큐먼트를 수정·삭제하지 않는다. 메시지 수정·삭제조차 기존 도큐먼트를 고치는 게 아니라 `message_edited`/`message_deleted` 이벤트를 새로 추가한다.

### 영속 이벤트 카탈로그

| type | payload 주요 필드 | 복원 시 효과 |
|---|---|---|
| `session_created` | `createdBy` | 빈 세션 초기화 |
| `participant_joined` | `userId` | 참여자 목록에 추가 |
| `participant_left` | `userId` | 참여자 목록에서 제거 |
| `message_sent` | `content` | 메시지 목록에 추가 |
| `message_edited` | `targetEventId`, `content` | 해당 메시지 본문 갱신 |
| `message_deleted` | `targetEventId` | 해당 메시지 soft-delete |
| `session_ended` | `endedBy` | 세션 상태 → ended |

`connect`/`disconnect`는 영속 이벤트로 두지 않는다 — 접속 상태는 본질적으로 휘발적이고, 네트워크가 불안정하면 재연결이 폭주해 append-only 로그를 오염시킨다. presence는 Redis TTL로 다룬다([관측·presence](observability.md) 참조).

## 복원 전략 — 스냅샷 + 리플레이

전체 이벤트 리플레이는 세션이 길어질수록 비용이 선형 증가한다. **가장 가까운 스냅샷에서 출발해 그 이후 이벤트만 적용**해 복원 비용 상한을 스냅샷 주기로 통제한다.

```
복원(session_id, at):
  snapshot ← snapshots 중 snapshotAt ≤ at 인 최신 1건  (없으면 빈 상태)
  events   ← events.find({ sessionId,
                           _id:       { $gt: snapshot.upToEventId },
                           serverTs:  { $lte: at } }).sort({ _id: 1 })
  state ← snapshot.state
  for e in events:  state ← reduce(state, e)
  return state
```

구현: `api-server`의 `TimelineApplicationService.restore(sessionId, at)`.

## 결정론의 근거 — 순수 함수 리듀서

복원의 결정론은 리듀서가 **순수 함수**라는 데서 나온다 — 외부 호출·랜덤·현재시각 참조가 없다. 같은 이벤트 집합을 같은 순서로 적용하면 항상 같은 결과가 나온다.

리듀서(`common.domain.session.SessionStateReducer`)는 `domain` 레이어에 두어 Spring·MongoDB에 의존하지 않는다. 덕분에 인프라 없이 단위 테스트로 결정론을 검증할 수 있다.

```java
SessionState reduce(SessionState state, Event event) {
  switch (event.payload()) {
    case ParticipantJoined p -> state.participants().add(p.userId());
    case ParticipantLeft   p -> state.participants().remove(p.userId());
    case MessageSent       p -> state.messages().put(event.id(), new MessageView(...SENT));
    case MessageEdited     p -> { if (대상 있음) 본문·status=EDITED 갱신; }  // 없으면 no-op
    case MessageDeleted    p -> { if (대상 있음) status=DELETED; }            // 없으면 no-op
    case SessionEnded      i -> state.status(ENDED);
    case SessionCreated    i -> { /* 빈 세션 — no-op */ }
  }
  return state;
}
```

`sealed interface EventPayload` + Java 21 switch pattern matching — 새 이벤트 타입이 생기면 컴파일러가 누락된 case를 강제로 잡아낸다.

### 중복·순서 처리

- **순서**: 항상 `sort({_id: 1})`로 읽으므로 도착 순서와 무관하게 동일 결과 (UUIDv7 정렬, [중복 처리 & 순서 보장](idempotency-ordering.md)).
- **중복**: 수집 단계에서 unique index로 이미 제거됐으므로 복원 시 이벤트 스트림에 중복이 없다.
- **방어**: `message_edited`/`message_deleted`의 `targetEventId`가 현재 상태에 없으면 no-op — 수정/삭제가 원본보다 먼저 와도 깨지지 않는다.

## 스냅샷 자동화

스냅샷 생성을 두 트리거로 조합한다 — 단일 트리거로는 구멍이 생긴다.

1. **이벤트 트리거** — `participant_left`/`session_ended` 발생 시 `@Async`로 즉시 한 번. 종료된 세션은 이후 이벤트가 안 쌓이므로 이 스냅샷이 "완성본"이 된다.
2. **스케줄러 배치** — `@Scheduled` 1시간 간격. "마지막 스냅샷 이후 이벤트가 임계치 N을 초과한 세션"만 골라 처리. 종료됐거나 조용한 세션은 새 이벤트가 없어 자동으로 대상에서 빠진다.

스냅샷 멱등성은 `snapshots {sessionId, upToEventId}` unique index가 보장 — 같은 지점의 중복 스냅샷을 막아 배치가 멱등하다. 임계치 N은 [부하 테스트](load-test.md)로 측정해 5000으로 조정했다.

## 검증

- `SessionStateReducerTest` (9건) — 같은 이벤트 → 같은 상태, shuffle 후 `_id` 정렬 동치성, 방어적 no-op, 메시지 edit/delete.
- `TimelineApplicationServiceTest` (3건) — 스냅샷 없을 때 전체 리플레이, **스냅샷 + 이후 이벤트 = 전체 리플레이 동치성**, `at` 시점 필터.
- E2E 시나리오 [6][7][9][10][12] — Edit/Delete가 timeline에 반영, SNAPSHOT resume, 결정론(스냅샷 state ≡ 같은 시점 timeline).

## 구현 PR

PR #2 (timeline 복원 + 리듀서), PR #3 (스냅샷 스케줄러).
