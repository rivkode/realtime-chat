package com.realtime.common.application;

import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStateReducer;
import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 스냅샷 생성 응용 서비스(설계서 §12.3).
 *
 * <p>동작:
 * <ol>
 *   <li>마지막 스냅샷 조회 — 있으면 그 state를 base, 없으면 빈 state부터</li>
 *   <li>{@code _id > lastSnapshot.upToEventId}인 이벤트를 시간순으로 가져옴</li>
 *   <li>새 이벤트가 없으면 skip(빈 결과 반환) — 같은 지점 중복 스냅샷 회피, 멱등 배치 근거</li>
 *   <li>리듀서로 fold → 마지막 이벤트 ID를 {@code upToEventId}로 잡아 저장</li>
 * </ol>
 *
 * <p>{@code snapshots {sessionId, upToEventId}} unique index가 race condition 안전망 역할.
 * 거의 발생하지 않지만(이벤트 트리거 + 스케줄러 동시 가능성) 위반 시 예외는 호출자에게 전달.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SnapshotApplicationService {

	private final SessionRepository sessionRepository;
	private final SnapshotRepository snapshotRepository;
	private final EventRepository eventRepository;
	private final Clock clock;

	/**
	 * 현재 시점까지 누적된 모든 이벤트를 fold해 스냅샷으로 저장한다.
	 *
	 * @return 새로 저장된 스냅샷. 새 이벤트가 없어 만들지 않은 경우 empty.
	 * @throws SessionNotFoundException 세션이 존재하지 않을 때
	 */
	public Optional<Snapshot> snapshotNow(UUID sessionId) {
		sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));

		Optional<Snapshot> last = snapshotRepository.findLatest(sessionId);
		UUID afterEventId = last.map(Snapshot::upToEventId).orElse(null);
		Instant now = clock.instant();

		List<Event> events = eventRepository.findForReplay(sessionId, afterEventId, now);
		if (events.isEmpty()) {
			return Optional.empty();
		}

		SessionState baseState = last.map(Snapshot::state).orElseGet(SessionState::empty);
		SessionState newState = SessionStateReducer.foldAll(baseState, events);
		UUID upToEventId = events.get(events.size() - 1).id();

		Snapshot snapshot = new Snapshot(
				UuidV7.generate(), sessionId, upToEventId, newState, now);
		return Optional.of(snapshotRepository.save(snapshot));
	}

	/**
	 * 비동기 wrapper — 이벤트 트리거(leave/end) 시 핫패스를 막지 않기 위해(§12.3 trigger 1).
	 * 같은 클래스 내 호출이면 프록시 우회되므로, 외부 빈(예: {@code SessionApplicationService})에서 호출할 것.
	 */
	@Async
	public void snapshotAsync(UUID sessionId) {
		try {
			snapshotNow(sessionId);
		} catch (Exception ex) {
			log.warn("Async snapshot failed for session {}: {}", sessionId, ex.getMessage(), ex);
		}
	}
}
