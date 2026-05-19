package com.realtime.chat.application;

import com.realtime.chat.application.ResumeService.Mode;
import com.realtime.chat.application.ResumeService.ResumeResult;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.InMemoryEventRepository;
import com.realtime.common.application.InMemorySessionRepository;
import com.realtime.common.application.InMemorySnapshotRepository;
import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeServiceTest {

	private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
	private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
	private final InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC);
	private final EventAppendService eventAppendService = new EventAppendService(eventRepository, fixedClock);
	private final ResumeService service = newServiceWithThresholds(1000, 200);

	@Test
	void unknown_session_throws() {
		assertThatThrownBy(() -> service.resume(UUID.randomUUID(), UUID.randomUUID(), null))
				.isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void incremental_returns_events_after_last_id() {
		Session session = activeSession();
		var first = appendMessage(session.id(), "hi");
		var second = appendMessage(session.id(), "hello");
		appendMessage(session.id(), "post-second");

		ResumeResult result = service.resume(session.id(), first.id(), null);

		assertThat(result.mode()).isEqualTo(Mode.INCREMENTAL);
		assertThat(result.events()).hasSize(2);
		assertThat(result.events().get(0).id()).isEqualTo(second.id());
		assertThat(result.lastEventId()).isEqualTo(result.events().get(2 - 1).id());
		assertThat(result.hasMore()).isFalse();
	}

	@Test
	void missing_last_event_id_uses_snapshot_path() {
		Session session = activeSession();
		appendMessage(session.id(), "hi");

		ResumeResult result = service.resume(session.id(), null, null);

		assertThat(result.mode()).isEqualTo(Mode.SNAPSHOT);
		assertThat(result.state()).isNotNull();
		// 스냅샷이 없으므로 빈 base에 이벤트 1건 fold → messages 1개
		assertThat(result.state().messages()).hasSize(1);
		assertThat(result.lastEventId()).isNotNull();
	}

	@Test
	void overflowing_catchup_falls_back_to_snapshot_path() {
		ResumeService tinyThreshold = newServiceWithThresholds(2, 200);
		Session session = activeSession();
		var first = appendMessage(session.id(), "1");
		appendMessage(session.id(), "2");
		appendMessage(session.id(), "3");
		appendMessage(session.id(), "4");

		ResumeResult result = tinyThreshold.resume(session.id(), first.id(), null);

		// first 이후 3건 > 임계치 2 → 스냅샷 경로
		assertThat(result.mode()).isEqualTo(Mode.SNAPSHOT);
		assertThat(result.state()).isNotNull();
		assertThat(result.state().messages()).hasSize(4); // empty base + 전체 fold
	}

	@Test
	void snapshot_path_uses_latest_snapshot_as_base() {
		Session session = activeSession();
		var first = appendMessage(session.id(), "before-snapshot");
		// 스냅샷 저장: first까지 반영된 상태
		SessionState pre = SessionState.empty();
		pre.messages().put(first.id(), new SessionState.MessageView(
				"user-1", "before-snapshot", SessionState.MessageStatus.SENT));
		snapshotRepository.save(new Snapshot(
				UuidV7.generate(), session.id(), first.id(), pre, fixedClock.instant()));
		// 스냅샷 이후 2건
		appendMessage(session.id(), "after-1");
		appendMessage(session.id(), "after-2");

		ResumeResult result = service.resume(session.id(), null, null);

		assertThat(result.mode()).isEqualTo(Mode.SNAPSHOT);
		assertThat(result.state().messages()).hasSize(3); // snapshot base 1 + tail 2
	}

	private Session activeSession() {
		Session session = Session.create(UUID.randomUUID(), "user-1", fixedClock.instant());
		sessionRepository.save(session);
		return session;
	}

	private com.realtime.common.domain.event.Event appendMessage(UUID sessionId, String content) {
		return eventAppendService.append(sessionId, "user-1", UUID.randomUUID(),
				new EventPayload.MessageSent(content), null).event();
	}

	private ResumeService newServiceWithThresholds(long catchUpThreshold, int defaultLimit) {
		ResumeService s = new ResumeService(sessionRepository, snapshotRepository, eventRepository);
		ReflectionTestUtils.setField(s, "catchUpThreshold", catchUpThreshold);
		ReflectionTestUtils.setField(s, "defaultLimit", defaultLimit);
		return s;
	}
}
