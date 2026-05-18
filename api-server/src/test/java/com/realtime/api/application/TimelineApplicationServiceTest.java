package com.realtime.api.application;

import com.realtime.api.application.TimelineApplicationService.TimelineResult;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.InMemoryEventRepository;
import com.realtime.common.application.InMemorySnapshotRepository;
import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStateReducer;
import com.realtime.common.domain.session.SessionStatus;
import com.realtime.common.domain.session.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimelineApplicationServiceTest {

	private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
	private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
	private final InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T15:00:00Z"), ZoneOffset.UTC);
	private final EventAppendService eventAppendService = new EventAppendService(eventRepository, fixedClock);
	private final SnapshotApplicationService snapshotApplicationService = new SnapshotApplicationService(
			sessionRepository, snapshotRepository, eventRepository, fixedClock);
	private final SessionApplicationService sessionService = new SessionApplicationService(
			sessionRepository, eventRepository, eventAppendService, snapshotApplicationService, fixedClock);
	private final TimelineApplicationService timelineService = new TimelineApplicationService(
			sessionRepository, snapshotRepository, eventRepository, fixedClock);

	@Test
	void unknown_session_throws() {
		assertThatThrownBy(() -> timelineService.restore(UUID.randomUUID(), null))
				.isInstanceOf(SessionNotFoundException.class);
	}

	@Test
	void restore_without_snapshot_replays_all_events() {
		Session session = sessionService.create("user-1");
		sessionService.join(session.id(), "user-1", UUID.randomUUID());
		sessionService.join(session.id(), "user-2", UUID.randomUUID());
		appendMessage(session.id(), "user-1", "hi");
		appendMessage(session.id(), "user-2", "hello");

		TimelineResult result = timelineService.restore(session.id(), null);

		assertThat(result.state().participants()).containsExactly("user-1", "user-2");
		assertThat(result.state().messages()).hasSize(2);
		assertThat(result.state().status()).isEqualTo(SessionStatus.ACTIVE);
	}

	@Test
	void restore_with_snapshot_equals_full_replay() {
		// 같은 이벤트를 (1) 전체 리플레이 / (2) 스냅샷 + 이후 이벤트로 복원한 결과가 동일해야 한다.
		// 설계서 §10의 핵심 약속 — 결정론.
		Session session = sessionService.create("user-1");
		sessionService.join(session.id(), "user-1", UUID.randomUUID());
		sessionService.join(session.id(), "user-2", UUID.randomUUID());
		Event m1 = appendMessage(session.id(), "user-1", "first");
		Event m2 = appendMessage(session.id(), "user-2", "second");

		// 전체 리플레이 (스냅샷 없음)
		TimelineResult full = timelineService.restore(session.id(), null);

		// m2 시점까지의 스냅샷을 만들고 그 뒤 더 진행
		SessionState midState = SessionState.empty();
		for (Event e : eventRepository.findAfter(session.id(), null, Integer.MAX_VALUE)) {
			midState = SessionStateReducer.reduce(midState, e);
		}
		snapshotRepository.save(new Snapshot(
				UuidV7.generate(), session.id(), m2.id(), midState, fixedClock.instant()));
		appendMessage(session.id(), "user-1", "third");

		// 스냅샷이 있는 상태로 복원 (이후 m3가 추가됨)
		TimelineResult withSnapshot = timelineService.restore(session.id(), null);

		// full에는 third가 없고 withSnapshot에는 있으니, full + third 적용 결과와 withSnapshot 비교
		SessionState expected = full.state();
		Event m3 = eventRepository.findAfter(session.id(), m2.id(), Integer.MAX_VALUE).get(0);
		SessionStateReducer.reduce(expected, m3);

		assertThat(withSnapshot.state().messages().keySet())
				.isEqualTo(expected.messages().keySet());
		assertThat(withSnapshot.state().participants())
				.isEqualTo(expected.participants());
	}

	@Test
	void at_filters_future_events() {
		// fixedClock보다 1시간 뒤를 sendClock으로, 그 사이 시점을 at으로 잡아 미래 이벤트가 제외되는지.
		Session session = sessionService.create("user-1");
		Instant midpoint = fixedClock.instant().plusSeconds(60);
		Instant futureClock = fixedClock.instant().plusSeconds(120);

		// 현재 시점 메시지
		sessionService.join(session.id(), "user-1", UUID.randomUUID());

		// 미래 시점 메시지 (serverTs를 직접 조작)
		Event futureEvent = new Event(
				UuidV7.generate(), session.id(), com.realtime.common.domain.event.EventType.MESSAGE_SENT,
				"user-1", UUID.randomUUID(),
				new EventPayload.MessageSent("future"),
				null, futureClock);
		eventRepository.append(futureEvent);

		TimelineResult atMidpoint = timelineService.restore(session.id(), midpoint);

		assertThat(atMidpoint.state().participants()).containsExactly("user-1");
		assertThat(atMidpoint.state().messages()).isEmpty(); // 미래 메시지 제외
	}

	private Event appendMessage(UUID sessionId, String userId, String content) {
		return eventAppendService.append(sessionId, userId, UUID.randomUUID(),
				new EventPayload.MessageSent(content), null).event();
	}
}
