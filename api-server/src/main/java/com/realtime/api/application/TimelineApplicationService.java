package com.realtime.api.application;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStateReducer;
import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 시점 복원 응용 서비스(설계서 §10).
 *
 * <pre>
 * snapshot ← snapshots 중 snapshotAt ≤ at 인 최신 1건  (없으면 빈 상태)
 * events   ← events.findForReplay(sessionId, snapshot.upToEventId, at)
 * state    ← fold(snapshot.state, events)
 * </pre>
 *
 * <p>리듀서는 {@link SessionStateReducer}의 순수 함수라 결정론이 보장된다(§10.2, CLAUDE.md).
 * {@code at}이 null이면 현재 시점(clock.instant()) 복원.
 */
@Service
@RequiredArgsConstructor
public class TimelineApplicationService {

	private final SessionRepository sessionRepository;
	private final SnapshotRepository snapshotRepository;
	private final EventRepository eventRepository;
	private final Clock clock;

	public TimelineResult restore(UUID sessionId, Instant at) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));

		Instant effectiveAt = at != null ? at : clock.instant();

		Optional<Snapshot> snapshot = (at == null)
				? snapshotRepository.findLatest(sessionId)
				: snapshotRepository.findLatestBefore(sessionId, at);

		SessionState baseState = snapshot.map(Snapshot::state).orElseGet(SessionState::empty);
		UUID afterEventId = snapshot.map(Snapshot::upToEventId).orElse(null);

		List<Event> events = eventRepository.findForReplay(sessionId, afterEventId, effectiveAt);
		SessionState state = SessionStateReducer.foldAll(baseState, events);

		return new TimelineResult(session.id(), effectiveAt, state);
	}

	public record TimelineResult(UUID sessionId, Instant at, SessionState state) {
	}
}
