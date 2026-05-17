package com.realtime.common.domain.event;

import java.util.UUID;

/**
 * 이벤트 타입별 payload. sealed로 타입 안전성을 확보한다(설계서 §6.2).
 * 모든 payload는 불변(record).
 */
public sealed interface EventPayload {

	EventType type();

	record SessionCreated(String createdBy) implements EventPayload {
		@Override public EventType type() { return EventType.SESSION_CREATED; }
	}

	record ParticipantJoined(String userId) implements EventPayload {
		@Override public EventType type() { return EventType.PARTICIPANT_JOINED; }
	}

	record ParticipantLeft(String userId) implements EventPayload {
		@Override public EventType type() { return EventType.PARTICIPANT_LEFT; }
	}

	record MessageSent(String content) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_SENT; }
	}

	record MessageEdited(UUID targetEventId, String content) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_EDITED; }
	}

	record MessageDeleted(UUID targetEventId) implements EventPayload {
		@Override public EventType type() { return EventType.MESSAGE_DELETED; }
	}

	record SessionEnded(String endedBy) implements EventPayload {
		@Override public EventType type() { return EventType.SESSION_ENDED; }
	}
}
