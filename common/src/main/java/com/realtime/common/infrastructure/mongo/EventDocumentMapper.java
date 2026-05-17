package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 도메인 {@link Event} ↔ 영속 {@link EventDocument} 매퍼.
 * payload는 Map으로 저장해 MongoDB의 도큐먼트 구조에 자연스럽게 매핑된다.
 */
public final class EventDocumentMapper {

	private EventDocumentMapper() {
	}

	public static EventDocument toDocument(Event event) {
		return new EventDocument(
				event.id(),
				event.sessionId(),
				event.type(),
				event.actorUserId(),
				event.clientEventId(),
				payloadToMap(event.payload()),
				event.clientTs(),
				event.serverTs()
		);
	}

	public static Event toDomain(EventDocument doc) {
		return new Event(
				doc.getId(),
				doc.getSessionId(),
				doc.getType(),
				doc.getActorUserId(),
				doc.getClientEventId(),
				mapToPayload(doc.getType(), doc.getPayload()),
				doc.getClientTs(),
				doc.getServerTs()
		);
	}

	private static Map<String, Object> payloadToMap(EventPayload payload) {
		Map<String, Object> map = new LinkedHashMap<>();
		switch (payload) {
			case EventPayload.SessionCreated p -> map.put("createdBy", p.createdBy());
			case EventPayload.ParticipantJoined p -> map.put("userId", p.userId());
			case EventPayload.ParticipantLeft p -> map.put("userId", p.userId());
			case EventPayload.MessageSent p -> map.put("content", p.content());
			case EventPayload.MessageEdited p -> {
				map.put("targetEventId", p.targetEventId().toString());
				map.put("content", p.content());
			}
			case EventPayload.MessageDeleted p -> map.put("targetEventId", p.targetEventId().toString());
			case EventPayload.SessionEnded p -> map.put("endedBy", p.endedBy());
		}
		return map;
	}

	private static EventPayload mapToPayload(EventType type, Map<String, Object> map) {
		return switch (type) {
			case SESSION_CREATED -> new EventPayload.SessionCreated((String) map.get("createdBy"));
			case PARTICIPANT_JOINED -> new EventPayload.ParticipantJoined((String) map.get("userId"));
			case PARTICIPANT_LEFT -> new EventPayload.ParticipantLeft((String) map.get("userId"));
			case MESSAGE_SENT -> new EventPayload.MessageSent((String) map.get("content"));
			case MESSAGE_EDITED -> new EventPayload.MessageEdited(
					UUID.fromString((String) map.get("targetEventId")),
					(String) map.get("content"));
			case MESSAGE_DELETED -> new EventPayload.MessageDeleted(
					UUID.fromString((String) map.get("targetEventId")));
			case SESSION_ENDED -> new EventPayload.SessionEnded((String) map.get("endedBy"));
		};
	}
}
