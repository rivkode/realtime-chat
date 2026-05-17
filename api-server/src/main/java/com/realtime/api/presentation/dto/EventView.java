package com.realtime.api.presentation.dto;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 이벤트 조회용 응답 DTO. payload는 평탄화된 Map으로 그대로 노출. */
public record EventView(
		UUID eventId,
		UUID sessionId,
		EventType type,
		String actorUserId,
		UUID clientEventId,
		Map<String, Object> payload,
		Instant clientTs,
		Instant serverTs
) {
	public static EventView of(Event event) {
		return new EventView(
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
}
