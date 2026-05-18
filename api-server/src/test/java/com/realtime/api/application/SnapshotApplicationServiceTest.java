package com.realtime.api.application;

import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.InMemoryEventRepository;
import com.realtime.common.application.InMemorySessionRepository;
import com.realtime.common.application.InMemorySnapshotRepository;
import com.realtime.common.application.SnapshotApplicationService;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotApplicationServiceTest {

	private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
	private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
	private final InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T16:00:00Z"), ZoneOffset.UTC);
	private final EventAppendService eventAppendService = new EventAppendService(eventRepository, fixedClock);
	private final SnapshotApplicationService snapshotService = new SnapshotApplicationService(
			sessionRepository, snapshotRepository, eventRepository, fixedClock);
	private final SessionApplicationService sessionService = new SessionApplicationService(
			sessionRepository, eventRepository, eventAppendService, snapshotService, fixedClock);

	@Test
	void unknown_session_throws() {
		assertThatThrownBy(() -> snapshotService.snapshotNow(UUID.randomUUID()))
				.isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void snapshot_with_no_events_returns_empty() {
		// 세션 메타만 있고 events 컬렉션엔 아무것도 없을 때 — 스냅샷 만들지 않음
		UUID sessionId = UUID.randomUUID();
		sessionRepository.save(Session.create(sessionId, "user-1", fixedClock.instant()));

		Optional<Snapshot> result = snapshotService.snapshotNow(sessionId);

		assertThat(result).isEmpty();
		assertThat(snapshotRepository.findLatest(sessionId)).isEmpty();
	}

	@Test
	void snapshot_from_empty_base_folds_all_events() {
		Session session = sessionService.create("user-1");
		sessionService.join(session.id(), "user-1", UUID.randomUUID());
		sessionService.join(session.id(), "user-2", UUID.randomUUID());
		appendMessage(session.id(), "user-1", "hi");

		Optional<Snapshot> result = snapshotService.snapshotNow(session.id());

		assertThat(result).isPresent();
		Snapshot snapshot = result.get();
		assertThat(snapshot.state().participants()).containsExactly("user-1", "user-2");
		assertThat(snapshot.state().messages()).hasSize(1);
		assertThat(snapshot.snapshotAt()).isEqualTo(fixedClock.instant());
	}

	@Test
	void snapshot_appends_only_new_events_on_top_of_existing() {
		// 같은 결과여야 하지만 처리 비용은 작아야 — 이건 동작 검증.
		// snapshotNow를 두 번 호출하면 두 번째는 첫 스냅샷 이후 이벤트만 fold해야 한다.
		Session session = sessionService.create("user-1");
		sessionService.join(session.id(), "user-1", UUID.randomUUID());
		Snapshot first = snapshotService.snapshotNow(session.id()).orElseThrow();

		// 추가 이벤트
		appendMessage(session.id(), "user-1", "post-snapshot");

		Snapshot second = snapshotService.snapshotNow(session.id()).orElseThrow();

		assertThat(second.upToEventId()).isNotEqualTo(first.upToEventId());
		assertThat(second.state().participants()).containsExactly("user-1");
		assertThat(second.state().messages()).hasSize(1); // post-snapshot 1건 누적
	}

	@Test
	void snapshot_is_idempotent_when_no_new_events_since_last() {
		// 첫 스냅샷 만들고, 이벤트 추가 없이 다시 snapshotNow → 두 번째는 empty
		Session session = sessionService.create("user-1");
		sessionService.join(session.id(), "user-1", UUID.randomUUID());
		snapshotService.snapshotNow(session.id());

		Optional<Snapshot> second = snapshotService.snapshotNow(session.id());

		assertThat(second).isEmpty();
	}

	private void appendMessage(UUID sessionId, String userId, String content) {
		eventAppendService.append(sessionId, userId, UUID.randomUUID(),
				new EventPayload.MessageSent(content), null);
	}
}
