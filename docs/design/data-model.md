# 데이터 모델

> 본 시스템은 MongoDB를 사용하므로 관계형 ERD/DDL 대신 **컬렉션 스키마 + 인덱스 설계**로 제출한다.
> 과제 명세 4.2는 정규화/비정규화/JSONB 등 선택을 자유로 하되 근거와 트레이드오프를 요구하며, 본 문서가 그에 답한다.

## 1. 컬렉션 구성

세 개의 컬렉션만 둔다. `events`가 진실의 원천이자 메시지 조회 소스이고, **별도 읽기 모델 컬렉션은 없다**.

```
events      모든 이벤트. append-only. 메시지 조회도 여기서.
sessions    세션 메타데이터(상태, 생성자). 작은 컬렉션.
snapshots   복원 최적화용 체크포인트.
```

### 왜 별도 읽기 모델(`messages`)을 두지 않는가

이벤트 소싱에서 흔히 읽기 모델을 별도 컬렉션으로 분리하지만, 본 설계는 두지 않는다. MongoDB는 단일 도큐먼트 쓰기를 원자적으로 보장하지만 여러 컬렉션에 걸친 트랜잭션은 샤딩 환경에서 비용이 든다. `events`와 `messages`를 따로 두면 둘을 한 트랜잭션으로 묶어야 정합성이 보장되는데, 이는 "샤딩하려고 MongoDB를 골랐는데 트랜잭션 때문에 샤딩이 무거워지는" 모순을 낳는다.

`events` 하나로 통일하면 이벤트 수집이 **단일 도큐먼트 INSERT 하나**로 끝난다. 멀티 도큐먼트 트랜잭션이 원천적으로 필요 없고, 큐도 CDC도 Outbox도 필요 없다. 메시지 조회 성능은 `type` + `session_id` 인덱스로 확보한다.

## 2. 컬렉션 스키마

### 2.1 `events` — 진실의 원천

```javascript
{
  _id:             UUID,        // UUIDv7 — 정렬·복원·재연결 커서의 기준
  sessionId:       UUID,
  type:            String,      // session_created | participant_joined | participant_left
                                // message_sent | message_edited | message_deleted | session_ended
  actorUserId:     String,
  clientEventId:   UUID,        // 클라이언트 생성 멱등 키
  payload:         Object,      // type별 가변 필드 (예: { content: "..." })
  clientTs:        ISODate,     // 참고용, 신뢰하지 않음 (클럭 스큐·조작 가능)
  serverTs:        ISODate,     // 서버 수신 시각
  traceId:         String       // §14.2 관측 — 한 메시지 경로 추적 ID
}
```

`payload`를 가변 Object로 둔 이유 — 이벤트 타입마다 필드가 다르다(`message_sent`는 `content`, `message_edited`는 `target_event_id`+`content`). 도큐먼트 모델은 이런 가변 스키마에 자연스럽다. 도메인 코드에서는 `sealed interface EventPayload`로 타입 안전성을 확보하고, 영속 시 평탄 Map으로 변환한다.

### 2.2 `sessions` — 세션 메타데이터

```javascript
{
  _id:        UUID,
  status:     String,      // ACTIVE | INTERRUPTED | ENDED
  createdBy:  String,
  createdAt:  ISODate,
  endedAt:    ISODate       // null 또는 종료 시각
}
```

`participants` 필드를 두지 않은 이유 — 참여자 목록은 `events`의 `participant_joined`/`participant_left`로 계산된다(D1, 별도 읽기 모델 없음). 1:1 채팅이라 집계 비용이 작다.

### 2.3 `snapshots` — 복원 체크포인트

```javascript
{
  _id:            UUID,
  sessionId:      UUID,
  upToEventId:    UUID,     // 이 이벤트까지 반영된 상태
  state:          {         // 리듀서 fold 결과
    participants: [String],
    messages:     [{ eventId, sender, content, status }],
    status:       String
  },
  snapshotAt:     ISODate
}
```

`state`는 임베디드 도큐먼트. 메시지가 많아지면 "최근 N개 + 총 개수"로 압축할 수 있다.

## 3. 인덱스 설계 (핫패스 중심)

| 인덱스 | 대상 쿼리 | 근거 |
|---|---|---|
| `events { sessionId: 1, _id: 1 }` | 재연결 증분 동기화, 시점 복원 리플레이 | 세션 단위 범위 스캔을 정렬된 순서로. UUIDv7이라 `_id` 정렬 = 시간 정렬 |
| `events { sessionId: 1, clientEventId: 1 }` **unique** | 중복 이벤트 차단 | 멱등 INSERT 시 충돌 감지 |
| `events { sessionId: 1, type: 1, _id: 1 }` | 메시지만 골라 최근 N개 조회 | 별도 `messages` 컬렉션 없이 `type` 필터로 메시지 추출 |
| `events { sessionId: 1, serverTs: 1 }` | `timeline?at=` 시간 필터 | `at` 이전 이벤트를 시간으로 끊음 |
| `snapshots { sessionId: 1, upToEventId: 1 }` **unique** | 같은 지점 중복 스냅샷 차단 | 스냅샷 배치 멱등성 보장 |
| `snapshots { sessionId: 1, snapshotAt: -1 }` | 복원 시 가장 가까운 스냅샷 | 세션별 최신 스냅샷 즉시 획득 |

`sessions`는 `status`·`createdBy`에 보조 인덱스(`@Indexed`)를 둬 목록 필터 쿼리를 지원한다.

핫패스별 쿼리·병목 분석은 [쿼리 최적화](../reports/query-optimization.md) 참조.

## 4. 샤딩 전략

`events`의 shard key는 **`session_id`**로 한다.
1. 한 세션의 모든 이벤트가 같은 샤드에 모여 세션 단위 조회·복원이 단일 샤드에서 끝난다(scatter-gather 회피).
2. 세션이 많아지면 자연히 여러 샤드로 분산된다.

단일 세션이 비대해지는 hot shard가 우려되면 `{session_id, _id}` 복합 키로 같은 세션도 분할할 수 있으나, 1:1 채팅은 세션당 트래픽이 제한적이라 `session_id` 단일 키로 충분하다.

## 5. 영속화 설정

| 항목 | 값 | 근거 |
|---|---|---|
| `uuidRepresentation` | `STANDARD` | BSON subtype 4. UUIDv7 byte layout이 그대로 저장되어 byte-by-byte 비교 = 시간순 정렬. 디폴트(JAVA_LEGACY)는 byte order를 reverse해 `_id` 정렬이 깨진다 |
| write concern | `MAJORITY` | 단일 도큐먼트 INSERT가 복제 다수에서 확인된 뒤 ACK — 부분 저장·노드 장애 시 손실 방지 |
| `waitQueueTimeoutMS` | `5000` | 커넥션 풀 고갈 시 5초 fail-fast — DB 지연이 서버 전체 다운으로 번지는 것 방지 |

## 6. 도메인/영속 모델 분리

DDD 원칙에 따라 도메인 객체와 영속 모델을 분리한다.

- **domain** (`common.domain`): `Event`, `Session`, `Snapshot` — 어노테이션 없는 순수 Java
- **infrastructure** (`common.infrastructure.mongo`): `EventDocument`, `SessionDocument`, `SnapshotDocument` — `@Document` 영속 모델 + 매퍼
- **Repository**: 도메인이 인터페이스로 정의, 구현은 각 서버 모듈의 `infrastructure`에 (의존성 역전)

이 분리로 도메인 로직(특히 이벤트 소싱 리듀서)이 MongoDB·Spring에 의존하지 않아 순수 함수로 유지되고, 복원 결정론 검증 테스트가 인프라 없이 가능하다.
