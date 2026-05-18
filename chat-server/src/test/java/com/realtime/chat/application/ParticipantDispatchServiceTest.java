package com.realtime.chat.application;

import com.realtime.chat.application.ParticipantDispatchService.ParticipantOutcome;
import com.realtime.chat.application.broadcast.PresenceStatus;
import com.realtime.chat.application.broadcast.SessionChannelMessage;
import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import com.realtime.chat.infrastructure.redis.PresenceStore;
import com.realtime.chat.infrastructure.redis.SessionChannelPublisher;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.InMemoryEventRepository;
import com.realtime.common.application.InMemorySessionRepository;
import com.realtime.common.application.InMemorySnapshotRepository;
import com.realtime.common.application.SnapshotApplicationService;
import com.realtime.common.domain.event.EventType;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionAlreadyEndedException;
import com.realtime.common.domain.session.SessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantDispatchServiceTest {

	private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
	private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
	private final InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T22:00:00Z"), ZoneOffset.UTC);
	private final EventAppendService eventAppendService = new EventAppendService(eventRepository, fixedClock);
	private final SnapshotApplicationService snapshotApplicationService = new SnapshotApplicationService(
			sessionRepository, snapshotRepository, eventRepository, fixedClock);
	private final RecordingPublisher publisher = new RecordingPublisher();
	private final InMemoryPresenceStore presenceStore = new InMemoryPresenceStore();
	private final PresenceService presenceService = new PresenceService(presenceStore, publisher, fixedClock);
	private final ParticipantDispatchService service = new ParticipantDispatchService(
			sessionRepository, eventAppendService, publisher, snapshotApplicationService, presenceService);

	@Test
	void join_appends_event_and_publishes_broadcast() {
		Session session = activeSession("user-1");

		ParticipantOutcome outcome = service.join(session.id(), "user-2", UUID.randomUUID());

		assertThat(outcome.duplicate()).isFalse();
		assertThat(outcome.event().type()).isEqualTo(EventType.PARTICIPANT_JOINED);
		assertThat(publisher.publishedEvents()).hasSize(1);
		assertThat(publisher.publishedEvents().get(0).eventId()).isEqualTo(outcome.event().id());
		assertThat(presenceStore.isOnline(session.id(), "user-2")).isTrue();
	}

	@Test
	void join_is_idempotent_under_same_client_event_id() {
		Session session = activeSession("user-1");
		UUID clientEventId = UUID.randomUUID();

		ParticipantOutcome first = service.join(session.id(), "user-2", clientEventId);
		ParticipantOutcome second = service.join(session.id(), "user-2", clientEventId);

		assertThat(second.duplicate()).isTrue();
		assertThat(second.event().id()).isEqualTo(first.event().id());
		assertThat(publisher.publishedEvents()).hasSize(1); // 중복은 event publish 생략
	}

	@Test
	void join_rejected_for_unknown_session() {
		assertThatThrownBy(() -> service.join(UUID.randomUUID(), "user-2", UUID.randomUUID()))
				.isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void join_rejected_for_ended_session() {
		Session session = activeSession("user-1");
		session.end(fixedClock.instant());
		sessionRepository.save(session);

		assertThatThrownBy(() -> service.join(session.id(), "user-2", UUID.randomUUID()))
				.isInstanceOf(SessionAlreadyEndedException.class);
	}

	@Test
	void leave_appends_event_publishes_and_triggers_snapshot() {
		Session session = activeSession("user-1");
		service.join(session.id(), "user-1", UUID.randomUUID());
		service.join(session.id(), "user-2", UUID.randomUUID());
		publisher.published.clear();

		ParticipantOutcome outcome = service.leave(session.id(), "user-2", UUID.randomUUID());

		assertThat(outcome.event().type()).isEqualTo(EventType.PARTICIPANT_LEFT);
		assertThat(publisher.publishedEvents()).hasSize(1);
		// §8.3 능동 전파: leave 시 PresenceBroadcast(OFFLINE)도 함께 발행
		assertThat(publisher.published.stream()
				.anyMatch(m -> m instanceof com.realtime.chat.application.broadcast.PresenceBroadcast pb
						&& pb.status() == PresenceStatus.OFFLINE
						&& pb.userId().equals("user-2"))).isTrue();
		assertThat(presenceStore.isOnline(session.id(), "user-2")).isFalse();
		// §12.3 trigger 1: leave 후 즉시 스냅샷 (@Async가 동기 실행되어 검증 가능)
		assertThat(snapshotRepository.findLatest(session.id())).isPresent();
	}

	@Test
	void leave_is_idempotent_under_same_client_event_id() {
		Session session = activeSession("user-1");
		service.join(session.id(), "user-1", UUID.randomUUID());
		UUID clientEventId = UUID.randomUUID();

		ParticipantOutcome first = service.leave(session.id(), "user-1", clientEventId);
		ParticipantOutcome second = service.leave(session.id(), "user-1", clientEventId);

		assertThat(second.duplicate()).isTrue();
		assertThat(second.event().id()).isEqualTo(first.event().id());
	}

	private Session activeSession(String createdBy) {
		Session session = Session.create(UUID.randomUUID(), createdBy, fixedClock.instant());
		sessionRepository.save(session);
		return session;
	}

	private static class RecordingPublisher extends SessionChannelPublisher {
		private final List<SessionChannelMessage> published = new ArrayList<>();

		RecordingPublisher() {
			super(null);
		}

		@Override
		public void publish(SessionChannelMessage message) {
			published.add(message);
		}

		List<SessionEventBroadcast> publishedEvents() {
			return published.stream()
					.filter(m -> m instanceof SessionEventBroadcast)
					.map(m -> (SessionEventBroadcast) m)
					.toList();
		}
	}

	private static class InMemoryPresenceStore extends PresenceStore {
		private final java.util.Set<String> online = ConcurrentHashMap.newKeySet();

		InMemoryPresenceStore() {
			super(null);
		}

		@Override
		public void markOnline(UUID sessionId, String userId) {
			online.add(key(sessionId, userId));
		}

		@Override
		public void markOffline(UUID sessionId, String userId) {
			online.remove(key(sessionId, userId));
		}

		@Override
		public boolean isOnline(UUID sessionId, String userId) {
			return online.contains(key(sessionId, userId));
		}

		private String key(UUID sessionId, String userId) {
			return sessionId + ":" + userId;
		}
	}
}
