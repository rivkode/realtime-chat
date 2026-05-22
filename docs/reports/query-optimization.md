# 쿼리 최적화 & 트러블슈팅

과제 명세 §4.4(1) — 대화 데이터가 대량으로 쌓일 때의 조회 성능 전략. 핫패스 3개 쿼리에 대해 쿼리·인덱스·예상 병목을 기술한다.

> 본 시스템은 MongoDB를 사용하므로 SQL 대신 MongoDB 쿼리로 제출한다. 인덱스 정의 전체는 [data-model.md](../design/data-model.md) 참조.

## 공통 전제

`events`는 무한 증가하는 append-only 컬렉션이다. 모든 핫패스 쿼리는 `session_id`를 선두로 한 복합 인덱스를 타도록 설계했고, `events`의 shard key를 `session_id`로 잡아 세션 단위 조회가 단일 샤드에서 완결되게 했다(scatter-gather 회피).

UUIDv7 `_id`는 시간순 정렬이므로 `sort({_id: 1})`이 별도 정렬 단계 없이 인덱스 순서를 그대로 쓴다 — 이것이 세 쿼리 모두의 공통 최적화다.

## Q1. 대화방 진입 — 최근 N개 메시지

```javascript
db.events.find({ sessionId: SID, type: "MESSAGE_SENT" })
         .sort({ _id: -1 })
         .limit(50)
```

- **인덱스**: `events { sessionId: 1, type: 1, _id: 1 }`
- **동작**: `sessionId` + `type` 등치 매칭으로 인덱스 범위를 좁히고, `_id` 역순(UUIDv7이라 최신순)으로 50개만 읽는다. 별도 `messages` 컬렉션 없이 `type` 필터로 메시지를 추출한다.
- **예상 병목**: `events`가 무한 성장 → 한 세션의 이벤트도 누적된다. 다만 인덱스가 `{sessionId, type, _id}`라 `limit(50)`이 인덱스 선두 50개만 읽고 멈춘다 — 컬렉션 크기와 무관하게 O(50). 컬렉션 전체 성장은 `session_id` shard key 분산으로 흡수한다.

## Q2. 재연결 증분 동기화 (Pull 복구)

```javascript
db.events.find({ sessionId: SID, _id: { $gt: lastEventId } })
         .sort({ _id: 1 })
```

- **인덱스**: `events { sessionId: 1, _id: 1 }`
- **동작**: `sessionId` 등치 + `_id > lastEventId` 범위 스캔. 인덱스가 정렬 순서 그대로라 정렬 비용 0. shard key가 `session_id`라 단일 샤드에서 완결.
- **예상 병목**: 오래 끊긴 클라이언트의 `lastEventId`가 매우 과거면 catch-up 대상이 수천 건으로 커진다. → **임계치 초과 시 스냅샷 기반 초기 로드로 전환**한다(`ResumeService`의 INCREMENTAL / SNAPSHOT 분기, [reconnect-recovery.md](reconnect-recovery.md)). 대량 범위를 일괄 전송하는 대신 스냅샷 + 최근 N개로 우회.

## Q3. 시점 복원 리플레이

```javascript
db.events.find({ sessionId: SID,
                 _id:       { $gt: upToEventId },
                 serverTs:  { $lte: atTime } })
         .sort({ _id: 1 })
```

- **인덱스**: `events { sessionId: 1, _id: 1 }`로 스캔, `serverTs`는 잔여 필터(residual filter)
- **동작**: 가장 가까운 스냅샷의 `upToEventId` 이후부터 `at` 시각까지의 이벤트를 시간순으로 읽어 리듀서로 fold한다.
- **예상 병목**: 스냅샷이 없거나 오래되면 리플레이 길이가 길어진다. → **스냅샷 주기로 복원 비용을 상한**짓는다. 스냅샷이 있으면 `_id > upToEventId`로 그 이후만 읽으므로, 리플레이 길이는 "스냅샷 임계치 N"으로 제한된다([부하 테스트](load-test.md)에서 N=5000으로 측정·확정).
- **`serverTs` 잔여 필터 트레이드오프**: `{sessionId, _id}` 인덱스로 스캔하고 `serverTs ≤ at`은 인덱스 밖에서 거른다. `{sessionId, serverTs}` 인덱스를 별도로 두어 `serverTs`를 인덱스로 끊을 수도 있으나, 복원은 `_id` 순서가 정본이라 `_id` 인덱스 스캔이 정렬을 공짜로 준다. 잔여 필터로 거르는 양이 스냅샷 주기 N 이하라 비용이 작다.

## 인덱스 설계 근거 요약

| 인덱스 | 핫패스 | 설계 의도 |
|---|---|---|
| `{sessionId, _id}` | Q2, Q3 | 세션 범위 스캔 + UUIDv7 정렬을 공짜로 |
| `{sessionId, type, _id}` | Q1 | `type` 필터를 인덱스 안에서 — 별도 `messages` 컬렉션 회피 |
| `{sessionId, serverTs}` | `timeline?at=` 시간 필터 보강 | `at`을 시간으로 끊어야 하는 변형 쿼리용 |
| `{sessionId, clientEventId}` unique | 이벤트 수집 | 멱등 — 중복 INSERT 차단 |

인덱스를 `session_id` 선두로 통일한 이유 — 모든 핫패스가 "한 세션 안에서"의 조회다. `session_id`를 선두 컬럼으로 두면 인덱스 prefix 매칭이 항상 성립하고, shard key와도 일치해 라우팅이 단순하다.

## 트러블슈팅 사례 — 실제로 겪은 병목

개발·검증 중 실제로 발견하고 고친 두 가지:

1. **UUID 정렬이 random처럼 깨짐** — `timeline` 응답의 메시지 순서가 뒤죽박죽으로 나왔다. 원인은 MongoDB가 UUID를 `JAVA_LEGACY`(BSON subtype 3) byte order로 저장해 UUIDv7의 timestamp prefix가 뒤로 밀린 것. `?uuidRepresentation=STANDARD`로 수정해 `_id` 정렬이 시간순으로 복원됐다([중복 처리 & 순서 보장](idempotency-ordering.md)).

2. **Redis 끊김 시 핫패스 hang** — Redis 정지 시 Lettuce 기본 60초 timeout 동안 `publish` thread가 hang해 ACK 송신이 막혔다. `spring.data.redis.timeout=2s` + circuit breaker로 fast-fail시켜 해결([장애 대응](fault-tolerance.md)).

## 검증

- 부하 테스트로 누적 건수별 `timeline` 복원 시간 측정 — 20000건 141ms ([load-test.md](load-test.md)).
- E2E 시나리오 [4] — `_id` 정렬이 송신 순서와 일치.
