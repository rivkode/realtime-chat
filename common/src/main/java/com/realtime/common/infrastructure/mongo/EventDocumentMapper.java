package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;

/**
 * 도메인 {@link Event} ↔ 영속 {@link EventDocument} 매퍼.
 * payload 평탄화는 {@link EventPayload#toMap()} / {@link EventPayload#fromMap}에 위임한다.
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
				event.payload().toMap(),
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
				EventPayload.fromMap(doc.getType(), doc.getPayload()),
				doc.getClientTs(),
				doc.getServerTs()
		);
	}
}
