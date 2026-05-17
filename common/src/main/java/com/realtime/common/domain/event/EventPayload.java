package com.realtime.common.domain.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 타입별 payload. sealed로 타입 안전성을 확보한다(설계서 §6.2).
 * 모든 payload는 불변(record).
 *
 * <p>{@link #toMap()} / {@link #fromMap}은 영속(Mongo) 매퍼와 REST controller가 공유한다 —
 * 도메인은 Jackson에 의존하지 않으므로(CLAUDE.md), 일반 {@code Map}으로 평탄화한다.
 */
public sealed interface EventPayload {

	EventType type();

	Map<String, Object> toMap();

	static EventPayload fromMap(EventType type, Map<String, Object> map) {
		Map<String, Object> source = map == null ? Map.of() : map;
		return switch (type) {
			case SESSION_CREATED -> new SessionCreated(string(source, "createdBy"));
			case PARTICIPANT_JOINED -> new ParticipantJoined(string(source, "userId"));
			case PARTICIPANT_LEFT -> new ParticipantLeft(string(source, "userId"));
			case MESSAGE_SENT -> new MessageSent(string(source, "content"));
			case MESSAGE_EDITED -> new MessageEdited(uuid(source, "targetEventId"), string(source, "content"));
			case MESSAGE_DELETED -> new MessageDeleted(uuid(source, "targetEventId"));
			case SESSION_ENDED -> new SessionEnded(string(source, "endedBy"));
		};
	}

	private static String string(Map<String, Object> source, String key) {
		Object value = source.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing payload field: " + key);
		}
		return value.toString();
	}

	private static UUID uuid(Map<String, Object> source, String key) {
		Object value = source.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing payload field: " + key);
		}
		if (value instanceof UUID uuid) return uuid;
		return UUID.fromString(value.toString());
	}

	record SessionCreated(String createdBy) implements EventPayload {
		@Override public EventType type() { return EventType.SESSION_CREATED; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("createdBy", createdBy);
			return m;
		}
	}

	record ParticipantJoined(String userId) implements EventPayload {
		@Override public EventType type() { return EventType.PARTICIPANT_JOINED; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("userId", userId);
			return m;
		}
	}

	record ParticipantLeft(String userId) implements EventPayload {
		@Override public EventType type() { return EventType.PARTICIPANT_LEFT; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("userId", userId);
			return m;
		}
	}

	record MessageSent(String content) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_SENT; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("content", content);
			return m;
		}
	}

	record MessageEdited(UUID targetEventId, String content) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_EDITED; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("targetEventId", targetEventId.toString());
			m.put("content", content);
			return m;
		}
	}

	record MessageDeleted(UUID targetEventId) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_DELETED; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("targetEventId", targetEventId.toString());
			return m;
		}
	}

	record SessionEnded(String endedBy) implements EventPayload {
		@Override public EventType type() { return EventType.SESSION_ENDED; }
		@Override public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("endedBy", endedBy);
			return m;
		}
	}
}
