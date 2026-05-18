package com.realtime.api.application;

import com.realtime.api.application.SessionApplicationService.EndOutcome;
import com.realtime.api.application.SessionApplicationService.JoinOutcome;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.InMemoryEventRepository;
import com.realtime.common.application.InMemorySnapshotRepository;
import com.realtime.common.domain.event.EventType;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionAlreadyEndedException;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionApplicationServiceTest {

	private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
	private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
	private final InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);
	private final EventAppendService eventAppendService = new EventAppendService(eventRepository, fixedClock);
	private final SnapshotApplicationService snapshotApplicationService = new SnapshotApplicationService(
			sessionRepository, snapshotRepository, eventRepository, fixedClock);
	private final SessionApplicationService service = new SessionApplicationService(
			sessionRepository, eventRepository, eventAppendService, snapshotApplicationService, fixedClock);

	@Test
	void create_persists_session_and_emits_session_created_event() {
		Session session = service.create("user-1");

		assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(eventRepository.findByClientEventId(session.id(), session.id()))
				.isPresent()
				.get()
				.satisfies(e -> assertThat(e.type()).isEqualTo(EventType.SESSION_CREATED));
	}

	@Test
	void join_is_idempotent_under_same_client_event_id() {
		Session session = service.create("user-1");
		UUID clientEventId = UUID.randomUUID();

		JoinOutcome first = service.join(session.id(), "user-2", clientEventId);
		JoinOutcome second = service.join(session.id(), "user-2", clientEventId);

		assertThat(first.duplicate()).isFalse();
		assertThat(second.duplicate()).isTrue();
		assertThat(second.event().id()).isEqualTo(first.event().id());
	}

	@Test
	void join_fails_for_unknown_session() {
		assertThatThrownBy(() -> service.join(UUID.randomUUID(), "user-2", UUID.randomUUID()))
				.isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void end_marks_session_ended_and_emits_event() {
		Session session = service.create("user-1");
		UUID clientEventId = UUID.randomUUID();

		EndOutcome outcome = service.end(session.id(), "user-1", clientEventId);

		assertThat(outcome.session().status()).isEqualTo(SessionStatus.ENDED);
		assertThat(outcome.session().endedAt()).isEqualTo(fixedClock.instant());
		assertThat(outcome.endedEvent().type()).isEqualTo(EventType.SESSION_ENDED);
	}

	@Test
	void end_is_idempotent_under_same_client_event_id() {
		Session session = service.create("user-1");
		UUID clientEventId = UUID.randomUUID();

		EndOutcome first = service.end(session.id(), "user-1", clientEventId);
		EndOutcome second = service.end(session.id(), "user-1", clientEventId);

		assertThat(first.endedEvent().id()).isEqualTo(second.endedEvent().id());
		assertThat(second.session().status()).isEqualTo(SessionStatus.ENDED);
	}

	@Test
	void join_after_end_is_rejected() {
		Session session = service.create("user-1");
		service.end(session.id(), "user-1", UUID.randomUUID());

		assertThatThrownBy(() -> service.join(session.id(), "user-2", UUID.randomUUID()))
				.isInstanceOf(SessionAlreadyEndedException.class);
	}

	@Test
	void end_triggers_immediate_snapshot() {
		// §12.3 trigger 1: session_ended 즉시 @Async 스냅샷. 단위 테스트에선 @Async가
		// 컨테이너 없이 동기 실행되어 같은 스레드에서 snapshotNow가 돈다.
		Session session = service.create("user-1");
		service.join(session.id(), "user-1", UUID.randomUUID());
		service.end(session.id(), "user-1", UUID.randomUUID());

		assertThat(snapshotRepository.findLatest(session.id())).isPresent();
	}
}
