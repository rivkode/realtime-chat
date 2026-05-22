# 다이어그램

## 1. 컴포넌트 다이어그램

```mermaid
flowchart TB
    U1["유저 1"]
    U2["유저 2"]

    subgraph SL["무상태 계층"]
        API["API 서버 :8080"]
    end
    subgraph ST["상태 유지 계층"]
        CS1["채팅 서버 1 :8081"]
        CS2["채팅 서버 2 :8082"]
    end
    subgraph DB["MongoDB"]
        EV[("events")]
        SE[("sessions")]
        SN[("snapshots")]
    end
    REDIS[("Redis<br/>Pub/Sub · presence TTL")]

    U1 -->|HTTP 세션 CRUD·timeline| API
    U2 -->|HTTP| API
    U1 -.->|WebSocket STOMP| CS1
    U2 -.->|WebSocket STOMP| CS2

    API --> EV
    API --> SE
    API --> SN
    CS1 --> EV
    CS2 --> EV
    CS1 <-->|PUBLISH / SUBSCRIBE| REDIS
    CS2 <-->|PUBLISH / SUBSCRIBE| REDIS
```

## 2. 시퀀스 — 메시지 송신 (서로 다른 채팅 서버)

설계서 §8.1. 유저 1은 채팅 서버 1, 유저 2는 채팅 서버 2에 붙어 있다.

```mermaid
sequenceDiagram
    participant U1 as 유저 1
    participant CS1 as 채팅 서버 1
    participant M as MongoDB
    participant R as Redis Pub/Sub
    participant CS2 as 채팅 서버 2
    participant U2 as 유저 2

    Note over U2,CS2: (사전) 유저 2가 channel:session:{id} 구독 중

    U1->>CS1: SEND /app/sessions/{id}/messages (client_event_id, content)
    CS1->>CS1: UUIDv7 event_id 생성
    CS1->>M: INSERT events (단일 도큐먼트 — 원자적)
    alt 중복 (client_event_id 충돌)
        M-->>CS1: duplicate key error → 기존 event 조회
        CS1-->>U1: ACK(기존 event_id, server_ts) -- 재전송 흡수
    else 신규
        M-->>CS1: 저장 완료
        CS1-->>U1: ACK(event_id, server_ts)
        CS1->>R: PUBLISH channel:session:{id}
        R->>CS2: deliver
        CS2->>U2: WebSocket push (/topic/sessions/{id})
    end
```

ACK는 "MongoDB에 저장됐다"는 의미이며 "상대가 봤다"는 의미가 아니다. MongoDB 저장 성공 이후의 Redis 발행은 best-effort다.

## 3. 시퀀스 — 재연결 resume (Pull 복구)

설계서 §9.3. 채팅 서버 장애 또는 네트워크 단절 후.

```mermaid
sequenceDiagram
    participant U2 as 유저 2
    participant CS as 채팅 서버 (새 인스턴스)
    participant R as Redis
    participant M as MongoDB

    U2->>CS: WebSocket 재연결 + SUBSCRIBE /topic/sessions/{id}
    CS->>R: SUBSCRIBE channel:session:{id} (앞으로의 라이브)
    U2->>CS: SEND /app/sessions/{id}/resume (last_event_id)
    CS->>M: find _id > last_event_id (catch-up)
    M-->>CS: 누락된 이벤트 일괄
    CS-->>U2: /user/queue/resume (mode=INCREMENTAL, events[])
    Note over U2,CS: catch-up 후 라이브 스트림으로 전환
```

`last_event_id`가 없거나 catch-up 대상이 임계치를 초과하면 `mode=SNAPSHOT`으로 전환 — 스냅샷 기반 현재 상태 + 새 `last_event_id`를 내려준다.

## 4. 상태 다이어그램 — 세션 수명주기

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: POST /sessions
    ACTIVE --> ENDED: session_ended (POST /sessions/{id}/end)
    ACTIVE --> INTERRUPTED: 비정상 종료 감지
    INTERRUPTED --> ACTIVE: 재개
    INTERRUPTED --> ENDED: 종료
    ENDED --> [*]
```

`INTERRUPTED`는 `ACTIVE`였던 세션이 비정상 종료로 감지된 상태다. 세션 상태(`status`)는 `sessions` 컬렉션의 메타데이터이며, 참여자 목록은 `events`의 `participant_joined`/`participant_left`로 별도 계산된다.

## 5. 상태 다이어그램 — 메시지 상태 (이벤트 소싱 리듀서)

설계서 §10.2. 메시지는 도큐먼트를 수정하지 않고 `message_edited`/`message_deleted` 이벤트를 새로 추가한다.

```mermaid
stateDiagram-v2
    [*] --> SENT: message_sent
    SENT --> EDITED: message_edited
    EDITED --> EDITED: message_edited (재수정)
    SENT --> DELETED: message_deleted
    EDITED --> DELETED: message_deleted
    DELETED --> [*]
```

`DELETED`는 soft-delete — 본문은 보존하고 status만 바꾼다. 리듀서는 `target_event_id`가 현재 상태에 없으면 no-op 처리해, 수정/삭제 이벤트가 원본보다 먼저 도착해도 깨지지 않는다.

## 6. presence 상태 (세션 참여/접속)

설계서 §8.3. 한 세션 안에서 상대의 상태는 셋으로 구분된다.

```mermaid
stateDiagram-v2
    [*] --> 접속중: join (presence 키 SET EX 30)
    접속중 --> 접속중: heartbeat (TTL 갱신)
    접속중 --> 끊김: heartbeat 중단 → TTL 만료 (수동 인지)
    끊김 --> 접속중: 재접속
    접속중 --> 세션떠남: participant_left (명시적 leave)
    끊김 --> 세션떠남: participant_left
    세션떠남 --> [*]
```

`접속중`/`끊김`은 presence(Redis TTL)로, `세션떠남`은 `participant_left` 이벤트로 판정한다. 멤버십과 접속 여부는 독립적이다 — 멤버이면서 끊겨 있을 수 있다.
