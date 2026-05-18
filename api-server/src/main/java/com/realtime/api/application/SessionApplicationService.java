package com.realtime.api.application;

import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.EventAppendService.AppendResult;
import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionAlreadyEndedException;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 세션 수명주기 응용 서비스(설계서 §16).
 *
 * <p>모든 멤버십 변경은 {@code events} INSERT를 동반한다(이벤트 소싱).
 * {@code sessions} 도큐먼트는 status·created_by·ended_at 같은 메타데이터만 보관하고,
 * 참여자 목록은 events의 {@code participant_joined}에서 집계한다(별도 읽기 모델 두지 않음, D1).
 *
 * <p>{@code session_created}의 멱등 키는 {@code sessionId} 자체로 둔다 — 세션 ID가 곧 유일성 보장.
 */
@Service
@RequiredArgsConstructor
public class SessionApplicationService {

	private final SessionRepository sessionRepository;
	private final EventRepository eventRepository;
	private final EventAppendService eventAppendService;
	private final SnapshotApplicationService snapshotApplicationService;
	private final Clock clock;

	public Session create(String createdBy) {
		Instant now = clock.instant();
		UUID sessionId = UuidV7.generate();
		Session session = Session.create(sessionId, createdBy, now);
		sessionRepository.save(session);
		eventAppendService.append(sessionId, createdBy, sessionId,
				new EventPayload.SessionCreated(createdBy), null);
		return session;
	}

	public JoinOutcome join(UUID sessionId, String userId, UUID clientEventId) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));
		if (session.status() == SessionStatus.ENDED) {
			throw new SessionAlreadyEndedException(sessionId);
		}
		AppendResult result = eventAppendService.append(sessionId, userId, clientEventId,
				new EventPayload.ParticipantJoined(userId), null);
		return new JoinOutcome(result.event(), session, result.duplicate());
	}

	public EndOutcome end(UUID sessionId, String endedBy, UUID clientEventId) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));
		AppendResult result = eventAppendService.append(sessionId, endedBy, clientEventId,
				new EventPayload.SessionEnded(endedBy), null);
		if (!result.duplicate() && session.status() != SessionStatus.ENDED) {
			session.end(result.event().serverTs());
			sessionRepository.save(session);
			// §12.3 trigger 1 — session_ended 즉시 스냅샷(@Async). 종료된 세션은 이후 이벤트가
			// 더 쌓이지 않으므로, 이 스냅샷이 사실상 그 세션의 "완성본"이 된다.
			snapshotApplicationService.snapshotAsync(sessionId);
		}
		return new EndOutcome(session, result.event());
	}

	public Session findById(UUID sessionId) {
		return sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));
	}

	public List<Session> find(SessionStatus status, String participantUserId,
							  Instant from, Instant to, UUID cursorId, int limit) {
		return sessionRepository.findByFilter(status, participantUserId, from, to, cursorId, limit);
	}

	public Set<String> findParticipants(UUID sessionId) {
		return eventRepository.findJoinedUserIds(sessionId);
	}

	public record JoinOutcome(Event event, Session session, boolean duplicate) {
	}

	public record EndOutcome(Session session, Event endedEvent) {
	}
}
