# 실시간 1:1 채팅 + 이벤트 소싱 기반 상태 복원 시스템

본 프로젝트는 Claude Code Opus 4.7을 사용해 구현했으며, 기술 스택은 **Java 21 · Spring Boot 3.5.14 · MongoDB · Redis · WebSocket(STOMP)** 를 사용한 멀티모듈 프로젝트입니다.

1:1 참여자 간 실시간 채팅과, 대화 중 발생한 이벤트를 기반으로 한 특정 시점 상태 복원을 다룹니다.

---

## 주요 의사결정 요약

| # | 결정 | 트레이드오프 |
|---|---|---|
| D1 | MongoDB `events` 컬렉션 **하나**가 진실의 원천이자 메시지 조회 소스 | 멀티 도큐먼트 트랜잭션 불필요 ↔ 조회 시 `type` 필터 인덱스 의존 |
| D2 | 이벤트 수집 = **단일 도큐먼트 INSERT** | 원자성 자동 보장, 큐·CDC 불필요 ↔ — |
| D3 | 정렬·복원의 기준은 **UUIDv7 이벤트 ID**(서버 수신 순서) | 단순, 인스턴스 간 조율 불필요 ↔ 클라이언트 의도 순서와 어긋날 수 있음 |
| D4 | 중복은 클라이언트 `client_event_id` + MongoDB **unique index**로 흡수 | 재전송이 안전 ↔ 클라이언트가 ID 생성 책임 |
| D5 | 라이브 전달은 Redis Pub/Sub, 채널 = `session_id` | 수신자 위치 추적 불필요 ↔ best-effort |
| D6 | 라이브 전달 유실 복구는 **전적으로 Pull 방식** | 추가 컴포넌트 0 ↔ 복구가 클라이언트 재접속/주기 sync에 의존 |
| D7 | 복원은 **스냅샷 + 이후 이벤트 결정론적 리플레이** | 복원 비용 상한 보장 ↔ 스냅샷 저장 비용 |
| D8 | 스냅샷은 leave/end 즉시 + **주기 스케줄 배치**로 생성 | 큐·워커 불필요 ↔ 최신 스냅샷이 약간 과거일 수 있음(리플레이가 메움) |

기술 선택 근거(MySQL 대비 MongoDB, Snowflake 대비 UUIDv7, Kafka 대비 Redis Pub/Sub)는 [시스템 아키텍처](docs/design/architecture.md) 문서에 상세히 기술합니다.

---

# 문서

## 설계

- [시스템 아키텍처](docs/design/architecture.md) — 무상태 API 계층 / 상태 유지 채팅 계층, data-flow, 기술 선택 근거
- [다이어그램](docs/design/diagrams.md) — 컴포넌트 · 시퀀스 · 상태 다이어그램
- [데이터 모델](docs/design/data-model.md) — MongoDB 컬렉션·인덱스 설계 + 핵심 스키마(ERD/DDL 대체)
- [API 명세](docs/design/api-spec.md) — REST + WebSocket(STOMP). 정식 스펙은 [`openapi.yaml`](openapi.yaml)

## 기술 보고서

본 과제에서 해결하려 노력한 기술 난제를 주제별로 정리합니다.

- [이벤트 소싱 & 상태 복원](docs/reports/event-sourcing-restore.md)
  - `events` 단일 컬렉션 + 순수 함수 리듀서로 결정론(Determinism) 보장. 스냅샷 + 이후 이벤트 리플레이로 복원 비용을 스냅샷 주기로 상한.
- [실시간 라이브 전달](docs/reports/realtime-delivery.md)
  - 채널을 `session_id`로 잡은 Redis Pub/Sub — 발신 서버가 "수신자가 어느 서버에 있는지" 몰라도 전달. Kafka를 두 층(라이브 부적합 + 비동기 불필요)으로 배제.
- [중복 처리 & 순서 보장](docs/reports/idempotency-ordering.md)
  - `{session_id, client_event_id}` unique index로 재전송 흡수. UUIDv7 `_id`로 서버 수신 순서를 일관 정렬 기준으로 채택.
- [재연결 정합성 & Pull 복구](docs/reports/reconnect-recovery.md)
  - 서버는 유실분을 추적하지 않는다. 수신 측이 `last_event_id` 이후를 당겨가는 Pull(재연결 resume + 주기 sync) — 추가 컴포넌트 0.
- [수평 확장 전략](docs/reports/scalability.md)
  - 세션을 특정 서버에 배치하지 않음. 세션을 채널로 추상화해 세션-서버 매핑 테이블·세션 마이그레이션 제거.
- [비동기 처리 설계](docs/reports/async-processing.md)
  - 단일 컬렉션 구조라 비동기 큐가 불필요한 이유와, 분리가 필요해지는 시점의 조건부 설계(멱등 upsert·지수 백오프·DLQ).
- [관측 가능성](docs/reports/observability.md)
  - 구조화 JSON 로그 + MDC, `trace_id` 전 구간 전파(events 도큐먼트 → Redis 페이로드 → 수신 서버 MDC), 단계별 카운터(received/persisted/published/delivered).
- [장애 대응 시나리오](docs/reports/fault-tolerance.md)
  - 채팅 서버 다운 / DB 장애 / Redis 장애 / 데이터 정합성 — 각각 감지 → 완화 → 복구. Resilience4j circuit breaker + Redis self-healing 구현.
- [쿼리 최적화 & 트러블슈팅](docs/reports/query-optimization.md)
  - 핫패스 3개 쿼리(최근 메시지 / 재연결 증분 / 시점 복원)의 인덱스 설계와 예상 병목.
- [부하 테스트 결과](docs/reports/load-test.md)
  - 50ms 간격 200건 시나리오 측정 — 처리 지연 p99, 복원 시간, 스냅샷 임계치 N 산정.

## 스터디

- [학습 노트](docs/study.md) — 본 과제를 수행하며 처음 학습한 내용 정리(이벤트 소싱, STOMP, UUIDv7, Redis Pub/Sub vs Kafka 등).

## 부록

- [원본 아키텍처 설계서](chat-system-architecture.md) — §1~§18 상세 설계 원본
- [구현 작업 분해](IMPLEMENTATION_PLAN.md) — 18개 기능 단위 분해
- [WebSocket 페이로드 모음](sample-payloads.md) — STOMP frame + 시나리오
- [E2E 테스트 가이드](e2e/README.md) — functional 18개 + 부하 테스트

---



---

# 실행 방법

## 환경 구성

| 구성 요소 | 버전 | 비고 |
|---|---|---|
| Java | 21 | Gradle toolchain |
| Spring Boot | 3.5.14 | 멀티모듈(`common` · `api-server` · `chat-server`) |
| MongoDB | 7 | 진실의 원천 — `events` / `sessions` / `snapshots` |
| Redis | 7 | Pub/Sub 라이브 전달 · presence TTL |
| Docker / Compose | 28+ / v2 | 로컬 전체 스택 |
| Node.js | 18+ | E2E 테스트 클라이언트 |

## 빠른 실행

```bash
# 1. 전체 스택 기동 (mongodb + redis + api-server + chat-server-1 + chat-server-2)
docker compose up -d --build
docker compose ps

# 2. 단위 테스트
./gradlew test

# 3. E2E functional 시나리오 (18개)
./e2e/run.sh --rebuild

# 4. 부하 테스트 (설계서 §18)
./e2e/run.sh --load
```

채팅 서버를 **2개** 띄우는 이유 — "유저 1은 `chat-server-1`(:8081)에, 유저 2는 `chat-server-2`(:8082)에 붙은" 상황을 로컬에서 재현해, 서로 다른 서버 간 Redis 채널 라이브 전달과 한 서버 장애 시 복구를 실제로 검증하기 위함입니다.

## 동작 검증

| 방법 | 설명 |
|---|---|
| 자동 E2E | `./e2e/run.sh` — 18개 functional 시나리오 자동 검증 ([가이드](e2e/README.md)) |
| 부하 테스트 | `./e2e/run.sh --load` — 처리 지연·순서 정합성·복원 시간 측정 |
| 수동 (브라우저) | `stomp-tester.html`을 브라우저로 열어 STOMP 송수신 ([페이로드 모음](sample-payloads.md)) |
| REST | `curl` 또는 Postman — [api-spec.md](docs/design/api-spec.md) 참조 |

## 모듈 구성

```
realtime/
├── common/        공유 도메인 모델 · 이벤트 정의 · 영속 스키마 · 공통 응용 서비스
├── api-server/    무상태 — 세션 CRUD · timeline 복원 · 스냅샷 스케줄러
├── chat-server/   상태 유지 — WebSocket · 이벤트 수집 · 라이브 전달 · presence (×N 인스턴스)
├── e2e/           Node.js 기반 E2E + 부하 테스트
└── docs/          설계 문서 · 기술 보고서
```

# 제출물 체크리스트 대응

| 과제 제출물 | 본 저장소 위치 |
|---|---|
| README — 실행 방법, 환경 구성, 의사결정 요약 | 이 문서 |
| API 명세 (OpenAPI 권장) | [`openapi.yaml`](openapi.yaml) + [api-spec.md](docs/design/api-spec.md) |
| ERD + 핵심 DDL | [data-model.md](docs/design/data-model.md) (MongoDB라 컬렉션 스키마로 대체) |
| 주요 쿼리 2~3개 + 인덱스 근거 + 병목 | [query-optimization.md](docs/reports/query-optimization.md) |
| 설계 문서 — 재연결·중복·확장성·관측·장애 대응 | [reconnect-recovery](docs/reports/reconnect-recovery.md) · [idempotency-ordering](docs/reports/idempotency-ordering.md) · [scalability](docs/reports/scalability.md) · [observability](docs/reports/observability.md) · [fault-tolerance](docs/reports/fault-tolerance.md) |
| 이벤트 기반 상태 복원 설계/구현 | [event-sourcing-restore.md](docs/reports/event-sourcing-restore.md) (**구현 완료**) |
| (선택) Snapshot/Projection 고도화 | [event-sourcing-restore.md](docs/reports/event-sourcing-restore.md) — 스냅샷 자동화 **구현 완료** |
| (선택) 부하 테스트 결과 | [load-test.md](docs/reports/load-test.md) (**측정 완료**) |
