package com.realtime.common.application;

import com.realtime.common.application.EventAppendService.AppendResult;
import com.realtime.common.domain.event.EventPayload;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventAppendServiceTest {

	private final InMemoryEventRepository repository = new InMemoryEventRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:34:56Z"), ZoneOffset.UTC);
	private final EventAppendService service = new EventAppendService(repository, fixedClock);

	@Test
	void first_append_stores_event_and_marks_not_duplicate() {
		UUID sessionId = UUID.randomUUID();
		UUID clientEventId = UUID.randomUUID();

		AppendResult result = service.append(sessionId, "user-1", clientEventId,
				new EventPayload.MessageSent("hi"), null);

		assertThat(result.duplicate()).isFalse();
		assertThat(result.event().sessionId()).isEqualTo(sessionId);
		assertThat(result.event().clientEventId()).isEqualTo(clientEventId);
		assertThat(result.event().serverTs()).isEqualTo(fixedClock.instant());
	}

	@Test
	void second_append_with_same_client_event_id_returns_existing_event() {
		UUID sessionId = UUID.randomUUID();
		UUID clientEventId = UUID.randomUUID();

		AppendResult first = service.append(sessionId, "user-1", clientEventId,
				new EventPayload.MessageSent("hi"), null);
		AppendResult second = service.append(sessionId, "user-1", clientEventId,
				new EventPayload.MessageSent("hi-retry"), null);

		assertThat(second.duplicate()).isTrue();
		assertThat(second.event().id()).isEqualTo(first.event().id());
	}
}
