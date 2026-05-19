package com.realtime.chat.application;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStateReducer;
import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 재연결 resume / 주기 sync 응용 서비스(설계서 §9.3 Pull 복구).
 *
 * <p>두 경로로 분기:
 * <ul>
 *   <li><b>증분 (incremental)</b> — {@code last_event_id}가 있고 catch-up 이벤트 수가 임계치 이하.
 *       {@code events.findAfter(sessionId, lastEventId, limit)}를 그대로 응답.</li>
 *   <li><b>스냅샷 기반 초기 로드 (snapshot)</b> — {@code last_event_id}가 없거나 catch-up이 임계치 초과.
 *       가장 최신 스냅샷의 state에 그 이후 이벤트를 fold해 현재 상태 + 새 {@code last_event_id}를 응답.</li>
 * </ul>
 *
 * <p>두 트리거가 같은 진입점을 공유한다 — 재연결 resume과 주기 sync는 클라이언트 측 호출 시점만
 * 다를 뿐 서버 로직은 동일(§9.3 "원인과 무관하게 '내가 가진 것 이후를 다 줘'가 항상 정답").
 *
 * <p>설계서 §9.3의 라이브 버퍼링 순서(SUBSCRIBE→버퍼→catch-up→합류)는 본 PR에선 클라이언트측
 * dedup으로 대체한다(event_id 중복 무시). UUIDv7 단조성 덕분에 catch-up과 라이브 사이 정확한
 * 정렬·중복 식별이 클라이언트에서 가능. 서버측 버퍼링은 후속 PR로 보류.
 */
@Service
@RequiredArgsConstructor
public class ResumeService {

	private final SessionRepository sessionRepository;
	private final SnapshotRepository snapshotRepository;
	private final EventRepository eventRepository;

	@Value("${resume.catchup-threshold:1000}")
	private long catchUpThreshold;

	@Value("${resume.default-limit:200}")
	private int defaultLimit;

	public ResumeResult resume(UUID sessionId, UUID lastEventId, Integer limit) {
		sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));

		int effectiveLimit = limit != null && limit > 0 ? limit : defaultLimit;

		if (lastEventId == null) {
			return snapshotInitialLoad(sessionId);
		}

		long pending = eventRepository.countAfter(sessionId, lastEventId);
		if (pending > catchUpThreshold) {
			return snapshotInitialLoad(sessionId);
		}

		List<Event> events = eventRepository.findAfter(sessionId, lastEventId, effectiveLimit);
		UUID newLastEventId = events.isEmpty()
				? lastEventId
				: events.get(events.size() - 1).id();
		boolean hasMore = events.size() == effectiveLimit;
		return new ResumeResult(Mode.INCREMENTAL, events, null, newLastEventId, hasMore);
	}

	private ResumeResult snapshotInitialLoad(UUID sessionId) {
		Optional<Snapshot> snapshot = snapshotRepository.findLatest(sessionId);
		SessionState baseState = snapshot.map(Snapshot::state).orElseGet(SessionState::empty);
		UUID afterEventId = snapshot.map(Snapshot::upToEventId).orElse(null);

		// 스냅샷 이후의 이벤트도 fold해 "현재 상태"로 갱신
		List<Event> tail = eventRepository.findAfter(sessionId, afterEventId, Integer.MAX_VALUE);
		SessionState currentState = SessionStateReducer.foldAll(baseState, tail);
		UUID newLastEventId = tail.isEmpty()
				? afterEventId
				: tail.get(tail.size() - 1).id();

		return new ResumeResult(Mode.SNAPSHOT, List.of(), currentState, newLastEventId, false);
	}

	public enum Mode {
		INCREMENTAL,
		SNAPSHOT
	}

	public record ResumeResult(
			Mode mode,
			List<Event> events,
			SessionState state,
			UUID lastEventId,
			boolean hasMore
	) {
	}
}
