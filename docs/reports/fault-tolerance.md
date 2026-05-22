# 장애 대응 시나리오

과제 명세 §4.4(3)이 요구한 세 상황을 **감지 → 완화 → 복구** 흐름으로 기술한다. Redis 장애를 더해 네 가지를 다룬다.

> 각 장애의 단계를 **✅ 구현**(코드로 동작·E2E 검증)과 **🔶 설계**(인프라 계층 — 로컬 Compose 범위 밖, 문서로 대체)로 구분해 기술한다.

## 1. 채팅 서버 다운 (인스턴스 장애)

| 단계 | 내용 | 상태 |
|---|---|---|
| **감지** | `/actuator/health` 엔드포인트 노출 (Mongo·Redis·circuit breaker indicator 포함) | ✅ 구현 (PR #8) |
| **감지** | 클라이언트가 WebSocket 끊김을 직접 감지 | ✅ 구현 (STOMP 연결 종료) |
| **감지** | 로드 밸런서가 `/actuator/health`를 폴링해 죽은 인스턴스 판별 | 🔶 설계 — LB는 인프라 계층, 로컬 Compose에 없음 |
| **완화** | 헬스체크 실패 서버를 LB 분배 대상에서 자동 제외 | 🔶 설계 — 로컬에선 클라이언트가 살아있는 서버로 직접 재연결해 LB 역할 재현 |
| **복구** | 클라이언트 자동 재연결 | ✅ 구현 (`reconnectDelay`) |
| **복구** | 새 서버가 `SUBSCRIBE channel:session:{id}` (앞으로의 라이브) | ✅ 구현 (PR #4) |
| **복구** | 클라이언트 `resume(last_event_id)` → 놓친 과거 catch-up | ✅ 구현 (PR #7) |

LB 기반 감지·완화는 인프라라 로컬에서 동작하지 않지만(설계로 대체), **핵심 메커니즘 — health 엔드포인트·재구독·resume catch-up — 은 모두 구현되어 E2E로 검증된다**(아래 §검증).

**무엇이 사라지고 무엇은 복구 대상이 아닌가** — 채팅 서버 인메모리에 있던 것은 살아있는 WebSocket 연결과 그 세션 매핑뿐이다. 둘 다 영속 데이터가 아니라 *살아있는 연결의 부산물*이다. 서버가 죽으면 WebSocket도 함께 끊기므로 "세션 X를 구독해야 한다"는 정보가 사라지는 게 아니라 — 구독할 *이유*였던 연결 자체가 사라진다. 따라서 인메모리 구독 매핑은 복구 대상이 아니다. 대화·세션·스냅샷은 MongoDB에, presence는 Redis에 있어 서버 장애와 무관하다.

복구 흐름: 재연결 → (LB가 있다면 자동, 없으면 클라이언트가 직접) 살아있는 서버로 라우팅 → 새 서버가 `SUBSCRIBE channel:session:{id}`(앞으로의 라이브) → 클라이언트가 `resume(last_event_id)`(놓친 과거)로 catch-up. 재구독(미래)과 resume(과거)은 시간 방향이 반대인 별개 동작이다. 상세: [재연결 정합성 & Pull 복구](reconnect-recovery.md).

> stateful 서버의 장애 복구는 "상태를 복구"하는 것이 아니라 애초에 "복구할 상태를 두지 않는" 설계로 푼다 — 채팅 서버 장애가 '연결 재수립'으로 끝나는 이유다.

**E2E 검증 (시나리오 [18])** — `docker compose stop chat-server-1`로 인스턴스 장애를 주입하고, 다운 동안 chat-server-2의 유저가 보낸 메시지 3건을, 재연결한 유저가 `resume`으로 전부 복구하는지 확인한다. docker-compose에 채팅 서버를 2대 띄운 이유가 바로 이 시나리오를 로컬에서 재현하기 위함이다.

## 2. DB 장애 / 성능 저하 (커넥션 고갈, 락 경합)

| 단계 | 내용 |
|---|---|
| **감지** | MongoDB 쓰기 지연 증가, replica set primary 선출 지연, 드라이버 커넥션 풀 사용률 임계 도달 |
| **완화** | 아래 |
| **복구** | failover·풀 회복 후 클라이언트 재전송분 유입 — `client_event_id` 멱등성 덕분에 중복 도착이 안전 |

**완화:**
- *노드 장애*: MongoDB replica set의 자동 failover로 secondary가 primary로 승격. 쓰기 불가 구간에는 클라이언트에 "전송 실패, 재시도 요망"을 명시 응답한다 — 저장 안 된 메시지를 저장됐다고 속이는 임의 ACK는 금지.
- *커넥션 고갈*: 드라이버 커넥션 풀은 상한이 정해져 있다. DB가 느려지면 요청이 커넥션을 오래 점유해 풀이 고갈되고, 그러면 서버가 새 작업을 처리하지 못한 채 무한 대기에 빠진다. 이를 막기 위해 `waitQueueTimeoutMS=5000`으로 커넥션 획득 대기에 타임아웃을 둬 풀이 비면 빠르게 실패시킨다(fail-fast). 대기를 무한정 쌓기보다 빠르게 실패해 클라이언트가 재시도하게 하는 것이 전체 가용성에 낫다.
- *락 경합*: 본 설계는 이 위험이 구조적으로 낮다. 이벤트 수집이 **단일 도큐먼트 INSERT**이고(D2), 여러 도큐먼트·여러 컬렉션에 걸친 트랜잭션이 없기 때문이다(D1). 핫패스에 멀티 도큐먼트 트랜잭션이 없으면 락이 걸리고 경합할 지점이 없다. 같은 세션에 동시 INSERT가 몰려도 도큐먼트가 서로 다르므로 충돌하지 않는다.

## 3. Redis 장애

| 단계 | 내용 |
|---|---|
| **감지** | Redis 연결 실패, Pub/Sub 발행 오류, presence 키 조회 실패 |
| **완화** | graceful degradation — Redis 호출 실패가 메시지 송신·MongoDB 저장·ACK를 막지 않는다 |
| **복구** | self-healing — Redis 복구 시 자동 재구독, presence는 heartbeat로 자연 재충전 |

**Redis가 보유한 것은 모두 휘발성이어도 되는 것뿐이다** — Pub/Sub 채널, presence(TTL). 둘 다 진실의 원천이 아니다.

**완화 — graceful degradation 구현:**
- `SessionChannelPublisher.publish`, `PresenceStore`의 Redis 호출에 Resilience4j `@CircuitBreaker` 적용. 연속 실패 시 OPEN으로 전환해 fast-fail — 죽어가는 Redis에 매 요청 연결을 시도하며 지연이 쌓이는 것을 막는다.
- `spring.data.redis.timeout=2s` — Lettuce 기본 60초 timeout 동안 publish thread가 hang하면 ACK 송신이 막힌다. 2초로 좁혀 핫패스가 빠르게 fallback으로 빠진다.
- 실패 시 fallback은 로그만 남기고 호출자에게 예외를 던지지 않는다 — 채팅 서버가 Redis 장애 때문에 함께 죽으면 안 된다.

**복구 — self-healing 구현:**
- 채널은 저장 대상이 아니다 — 채팅 서버가 자기 인메모리 연결 테이블(`ConnectionRegistry`)을 보고 Redis 재연결 시 해당 세션 채널을 다시 `SUBSCRIBE`한다. `RedisSelfHealingScheduler`가 주기적으로 `trackedSessionIds()`를 멱등 재구독해, Lettuce 자동 재연결 직후 detach된 listener를 자연히 다시 잡는다.
- presence는 클라이언트 heartbeat로 self-healing — Redis 복구 후 다음 heartbeat 주기에 저절로 다시 채워진다.

**정직한 한계** — Redis가 죽은 동안 유저 1이 보낸 메시지는 MongoDB에 저장되지만 publish가 실패한다. 유저 2가 온라인이고 WebSocket이 멀쩡하면 재연결할 이유가 없으므로 다음 주기적 sync 시점까지 새 메시지를 모른다. Pull 복구는 "데이터를 잃지 않음"은 보장하지만 "실시간성을 잃지 않음"은 보장하지 않는다. 운영에서는 Redis Sentinel 자동 failover로 "Redis 완전 다운" 상태를 수 초로 줄여 대응한다(Sentinel은 운영 설계라 로컬 Compose에는 포함하지 않음).

## 4. 데이터 유실 / 정합성 이슈 (중복 저장, 부분 실패)

| 단계 | 내용 |
|---|---|
| **감지** | 이벤트 수집은 단일 도큐먼트 INSERT라 "부분 저장"이 원천적으로 없다 |
| **완화** | 중복은 unique index로 차단. 별도 읽기 모델이 없으므로 "이벤트와 읽기 모델 불일치" 자체가 발생하지 않는다 |
| **복구** | `snapshots`가 손상되면 `events`에서 리플레이해 재생성한다 |

- **중복 저장**: `{session_id, client_event_id}` unique index가 차단([중복 처리 & 순서 보장](idempotency-ordering.md)).
- **부분 실패**: 멀티 도큐먼트 트랜잭션이 없으므로 "여러 컬렉션 중 일부만 저장"이 발생할 수 없다. write concern `majority`로 복제 다수 확인 후 ACK한다.
- **스냅샷 손상**: `events`가 진실의 원천이므로 스냅샷 손상은 데이터 손실이 아니라 재구축 대상일 뿐이다. `events`에서 리플레이해 재생성한다.

## 검증

- E2E 시나리오 [13] — `/actuator/health`로 mongo/redis/circuit breaker 상태 노출 (감지 수단).
- E2E 시나리오 [16] — Redis stop 중 events INSERT 계속 + ACK 정상 (Redis 장애 graceful degradation).
- E2E 시나리오 [17] — Redis start 후 self-healing 재구독 → 라이브 전달 재개.
- E2E 시나리오 [18] — **채팅 서버 인스턴스 다운** → 다른 서버로 재연결 → `resume` catch-up으로 다운 동안 누락분 전부 복구.
- E2E 시나리오 [5] — 같은 `client_event_id` 재전송 시 중복 저장 차단 (정합성).

## 구현 PR

PR #8 (Actuator + waitQueueTimeoutMS), PR #11 (circuit breaker + self-healing).
