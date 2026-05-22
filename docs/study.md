# 학습 노트

본 과제를 수행하며 처음 학습했거나, 알고 있다고 생각했지만 실제로 부딪혀 다시 이해하게 된 내용을 정리한다.

## 1. 이벤트 소싱 (Event Sourcing)

**처음 이해한 것** — "상태를 저장하지 않고 사건의 시퀀스를 저장한다"는 문장은 알았지만, 그것이 *왜* 특정 시점 복원을 가능하게 하는지는 직접 구현하며 체감했다. 현재 상태는 이벤트를 fold한 결과이고, 일부 이벤트까지만 fold하면 그 시점의 상태가 된다 — 시점 복원이 별도 기능이 아니라 fold의 자연스러운 부산물이다.

**메시지 수정/삭제도 이벤트** — 처음엔 메시지 도큐먼트를 직접 UPDATE하려 했으나, 그러면 "수정 전 시점"을 복원할 수 없다. `message_edited`/`message_deleted`를 새 이벤트로 추가하고 리듀서가 fold하는 방식으로 바꿔야 append-only가 유지된다.
## 2. Redis Pub/Sub vs Kafka — 메시지의 "수명"

**가장 크게 바뀐 이해** — 처음엔 "실시간성 vs 확장성"으로 둘을 구분했는데, 정확한 축은 **메시지의 수명(lifetime)**이었다.

- Redis Pub/Sub = 전달 통로. 저장하지 않는다. 발행 순간 구독자에게 밀고 끝.
- Kafka = 영속 로그. 디스크에 보관하고 오프셋을 추적한다.

라이브 전달은 "지금 연결된 상대에게 한 번 푸시"하면 끝나는 일이라 전달 통로가 맞다. 또 라이브 전달은 모든 서버가 전체 이벤트를 봐야 하는 브로드캐스트인데, Kafka 컨슈머 그룹은 파티션을 나눠 갖는 분담 모델이라 상반된다. "도구의 결함이 아니라 오용"이라는 관점을 배웠다.

**Redis 채널 ≠ Kafka 토픽** — Kafka 토픽은 물리적(파일 핸들·메모리)이라 수백만 개는 부담이지만, Redis 채널은 구독자 목록 키 하나라 가볍다. "세션마다 채널을 만들면 채널이 너무 많아진다"는 우려가 잘못된 대상이었음을 배웠다.

## 3. UUIDv7 (RFC 9562)

**처음 학습** — UUID에 시간 정렬 가능한 버전(v7)이 있다는 것. 앞 48bit가 unix 밀리초 타임스탬프라 ID 자체가 시간순으로 정렬된다.

**Snowflake와 비교하며 배운 것** — 둘 다 시간 정렬이 되지만, Snowflake는 워커ID를 인스턴스마다 유일하게 배분해야 한다. UUIDv7은 그 조율이 불필요하다 — 각 서버가 인메모리에서 독립 발급해도 충돌이 없다.

**부딪힌 문제 — MongoDB의 uuidRepresentation.** UUID를 BSON에 저장할 때 `JAVA_LEGACY`(byte order reverse)와 `STANDARD`(RFC 그대로)가 다르다. LEGACY로 저장되면 UUIDv7의 timestamp prefix가 byte 끝으로 밀려 정렬이 random처럼 깨진다. 실제로 `timeline` 응답의 메시지 순서가 뒤죽박죽 나오는 버그를 겪고, `?uuidRepresentation=STANDARD`로 수정했다. **"UUIDv7이라 정렬된다"는 것은 저장 형식이 byte order를 보존할 때만 참이다.**

**같은 ms 내 단조성** — 직접 구현하면 같은 밀리초에 생성된 두 UUID의 순서가 random 비트에 좌우된다. java-uuid-generator(JUG)의 `timeBasedEpochGenerator()`는 monotonic counter로 같은 ms 안에서도 단조 증가를 보장한다. 직접 구현 대신 검증된 라이브러리를 쓰는 게 맞았다.

## 4. 관측 가능성 — 카운터와 trace의 역할 분리

**배운 것** — 단계별 카운터(received/persisted/published/delivered)와 trace_id는 역할이 다르다. 카운터는 "어느 구간이 이상한가"(집계), trace_id는 "이 한 건이 어디서 멈췄나"(개별 추적). 특히 `published`(발신 서버)와 `delivered`(수신 서버)는 서로 다른 서버·다른 시점의 집계라 직접 빼서 유실을 단정할 수 없다 — 추세 비교로 "조사 트리거"를 삼고, 확정은 trace로 한다.
