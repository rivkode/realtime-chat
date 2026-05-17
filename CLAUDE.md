# CLAUDE.md

실시간 1:1 채팅 + 이벤트 소싱 기반 상태 복원 시스템.
구현 전 반드시 `chat-system-architecture-v4.md`(아키텍처 설계서)를 먼저 읽고, 그 설계를 따를 것.

## 기술 스택

- Java / Spring Boot
- MongoDB (진실의 원천, 단일 DB) — `events` / `sessions` / `snapshots` 컬렉션
- Redis (Pub/Sub 라이브 전달, presence TTL)
- WebSocket (STOMP)
- 빌드: Gradle 멀티모듈
- 외부 서비스는 MongoDB, Redis 둘뿐. Kafka·MySQL·Debezium 등은 쓰지 않는다(설계서 §1.2 근거).

## 멀티모듈 구조

- `api-server` — 무상태. 세션 CRUD, timeline 복원, 스냅샷 스케줄러.
- `chat-server` — 상태 유지. WebSocket·이벤트 수집·라이브 전달·presence. 인스턴스 여러 개로 실행됨.
- `common` — 두 모듈이 공유하는 도메인 모델·이벤트 정의·DTO.
- `docker-compose.yml` — api-server 1, chat-server-1, chat-server-2, mongodb, redis.

## 아키텍처 원칙 — DDD (헥사고날 아님)

레이어드 DDD를 따른다. 헥사고날(포트-어댑터 전면 적용)은 이 프로젝트 규모에 과설계라 채택하지 않는다.

레이어와 의존성 방향 (위 → 아래로만 의존):

```
presentation  (Controller, WebSocket Handler, STOMP)
      ↓
application   (UseCase / Service, 트랜잭션 경계, 흐름 조율)
      ↓
domain        (순수 도메인 모델, 도메인 서비스, Repository 인터페이스)
      ↑
infrastructure (Repository 구현, @Document 영속 모델, Redis, 외부 연동)
```

- **domain 레이어는 어떤 프레임워크·인프라도 참조하지 않는다.** Spring·MongoDB·Redis 어노테이션 금지. 순수 Java 객체와 비즈니스 규칙만.
- domain은 Repository를 **인터페이스로만** 정의한다. 구현은 infrastructure에 둔다(의존성 역전).
- application은 domain에만 의존하고 infrastructure 구현체를 직접 참조하지 않는다(인터페이스로 주입받음).
- 이벤트 소싱의 리듀서·복원 로직은 domain 레이어의 **순수 함수**로 둔다(설계서 §10.2). 외부 호출·현재시각·랜덤 금지 — 복원 결정론을 위해.

## 영속화 — 도메인/Document 분리

MongoDB 저장 시에도 도메인 객체와 영속 모델을 분리한다. 상세 방법은 `ddd-mongo-persistence` 스킬을 따른다. 요약:

- domain: 순수 도메인 객체 (`Event`, `Session` — 어노테이션 없음)
- infrastructure: `@Document` 영속 모델 (`EventDocument` 등) + 매퍼 + Repository 구현
- JPA Entity 패턴은 쓰지 않는다(MySQL 미사용). MongoDB `@Document`로 동일한 분리를 한다.

## 코드 컨벤션

- 의존성 주입은 **`@RequiredArgsConstructor` + `private final`** 필드 방식. `@Autowired` 필드 주입 금지.
- 롬복 사용. 단 도메인 객체에는 `@Data`를 쓰지 말 것(무분별한 setter 노출 금지) — 도메인은 의미 있는 메서드로 상태를 바꾼다.
- 불변성을 선호한다. DTO·이벤트 payload는 가능한 한 불변으로.
- 매직 넘버 금지. 설계서에 나온 값(heartbeat 10초, presence TTL 30초, 스냅샷 임계치 등)은 상수·설정으로.

## Git 워크플로 (중요)

- **기능 단위로 브랜치를 생성**한다. 브랜치명 예: `feature/event-collection`, `feature/timeline-restore`.
- 한 기능이 끝나면 **PR(Pull Request)을 생성**한다.
- **머지는 절대 직접 하지 않는다.** PR 생성까지만 하고, 머지는 사람(저장소 소유자)이 한다.
- `main` 브랜치에 직접 커밋·푸시하지 않는다.
- 커밋은 의미 단위로 잘게. 커밋 메시지는 무엇을·왜 바꿨는지 한국어 또는 영어로 명확히.
- 큰 기능은 시작 전에 구현 계획을 먼저 제시하고 동의를 받은 뒤 진행한다.

## 작업 방식

- 구현 전 항상 아키텍처 설계서의 관련 절을 확인하고, 설계와 어긋나면 임의로 바꾸지 말고 먼저 질문한다.
- 설계서에 없는 결정이 필요하면 가정을 명시하고 진행한다.
- 테스트를 함께 작성한다. 특히 이벤트 소싱 복원 로직은 결정론 검증 테스트(같은 이벤트 → 같은 상태)를 반드시 포함한다.
- 한 번에 너무 많은 파일을 바꾸지 말고, 기능 단위로 작게 진행한다.