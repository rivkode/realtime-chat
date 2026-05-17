package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.event.EventType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code events} 컬렉션 영속 모델(설계서 §7.2).
 * 도메인 {@link com.realtime.common.domain.event.Event}와 분리됨(CLAUDE.md DDD 원칙).
 * 인덱스 정의(설계서 §7.3):
 * <ul>
 *   <li>{@code {sessionId, _id}} — 범위 스캔·정렬</li>
 *   <li>{@code {sessionId, clientEventId} unique} — 멱등 INSERT 충돌 감지(§9.1)</li>
 *   <li>{@code {sessionId, type, _id}} — 메시지만 골라 최근 N개(§11 Q1)</li>
 *   <li>{@code {sessionId, serverTs}} — {@code timeline?at=} 시간 필터</li>
 * </ul>
 */
@Document(collection = "events")
@CompoundIndexes({
		@CompoundIndex(name = "idx_session_id", def = "{'sessionId': 1, '_id': 1}"),
		@CompoundIndex(name = "idx_session_client_event_unique",
				def = "{'sessionId': 1, 'clientEventId': 1}", unique = true),
		@CompoundIndex(name = "idx_session_type_id", def = "{'sessionId': 1, 'type': 1, '_id': 1}"),
		@CompoundIndex(name = "idx_session_server_ts", def = "{'sessionId': 1, 'serverTs': 1}")
})
public class EventDocument {

	@Id
	private UUID id;
	private UUID sessionId;
	private EventType type;
	private String actorUserId;
	private UUID clientEventId;
	private Map<String, Object> payload;
	private Instant clientTs;
	private Instant serverTs;

	public EventDocument() {
	}

	public EventDocument(UUID id, UUID sessionId, EventType type, String actorUserId,
						 UUID clientEventId, Map<String, Object> payload,
						 Instant clientTs, Instant serverTs) {
		this.id = id;
		this.sessionId = sessionId;
		this.type = type;
		this.actorUserId = actorUserId;
		this.clientEventId = clientEventId;
		this.payload = payload;
		this.clientTs = clientTs;
		this.serverTs = serverTs;
	}

	public UUID getId() { return id; }
	public UUID getSessionId() { return sessionId; }
	public EventType getType() { return type; }
	public String getActorUserId() { return actorUserId; }
	public UUID getClientEventId() { return clientEventId; }
	public Map<String, Object> getPayload() { return payload; }
	public Instant getClientTs() { return clientTs; }
	public Instant getServerTs() { return serverTs; }
}
