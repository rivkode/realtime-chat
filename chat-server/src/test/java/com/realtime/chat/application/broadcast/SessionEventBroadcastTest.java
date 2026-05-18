package com.realtime.chat.application.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEventBroadcastTest {

	private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

	@Test
	void from_event_extracts_payload_to_flat_map() {
		UUID sessionId = UUID.randomUUID();
		Event event = new Event(
				UuidV7.generate(),
				sessionId,
				EventType.MESSAGE_SENT,
				"user-1",
				UUID.randomUUID(),
				new EventPayload.MessageSent("hi"),
				null,
				Instant.parse("2026-05-18T20:00:00Z")
		);

		SessionEventBroadcast broadcast = SessionEventBroadcast.of(event);

		assertThat(broadcast.eventId()).isEqualTo(event.id());
		assertThat(broadcast.sessionId()).isEqualTo(sessionId);
		assertThat(broadcast.type()).isEqualTo(EventType.MESSAGE_SENT);
		assertThat(broadcast.actorUserId()).isEqualTo("user-1");
		assertThat(broadcast.payload()).containsEntry("content", "hi");
		assertThat(broadcast.serverTs()).isEqualTo(event.serverTs());
	}

	@Test
	void broadcast_round_trips_through_json() throws Exception {
		Event event = new Event(
				UuidV7.generate(),
				UUID.randomUUID(),
				EventType.MESSAGE_SENT,
				"user-1",
				UUID.randomUUID(),
				new EventPayload.MessageSent("hello"),
				null,
				Instant.parse("2026-05-18T20:00:00Z")
		);
		SessionEventBroadcast original = SessionEventBroadcast.of(event);

		String json = objectMapper.writeValueAsString(original);
		SessionEventBroadcast deserialized = objectMapper.readValue(json, SessionEventBroadcast.class);

		assertThat(deserialized.eventId()).isEqualTo(original.eventId());
		assertThat(deserialized.sessionId()).isEqualTo(original.sessionId());
		assertThat(deserialized.type()).isEqualTo(original.type());
		assertThat(deserialized.payload()).isEqualTo(original.payload());
		assertThat(deserialized.serverTs()).isEqualTo(original.serverTs());
	}
}
