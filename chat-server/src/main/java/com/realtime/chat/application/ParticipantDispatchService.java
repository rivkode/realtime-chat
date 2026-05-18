package com.realtime.chat.application;

import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import com.realtime.chat.infrastructure.redis.SessionChannelPublisher;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.EventAppendService.AppendResult;
import com.realtime.common.application.SnapshotApplicationService;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionAlreadyEndedException;
import com.realtime.common.domain.session.SessionNotFoundException;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 채팅 서버 측 join / leave 응용 서비스(설계서 §8.2).
 *
 * <p>흐름은 메시지 송신과 동일한 멱등 경로를 탄다:
 * <ol>
 *   <li>세션 존재 검증(없으면 {@link SessionNotFoundException}). join은 ENDED 세션 거부</li>
 *   <li>{@link EventAppendService}로 {@code participant_joined} / {@code participant_left} 이벤트 INSERT</li>
 *   <li>중복이 아니면 세션 채널로 publish — 상대가 참여/퇴장을 인지</li>
 * </ol>
 *
 * <p>{@code leave} 시 §12.3 trigger 1에 따라 {@link SnapshotApplicationService#snapshotAsync}로
 * 즉시 스냅샷을 비동기 트리거. 핫패스는 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ParticipantDispatchService {

	private final SessionRepository sessionRepository;
	private final EventAppendService eventAppendService;
	private final SessionChannelPublisher sessionChannelPublisher;
	private final SnapshotApplicationService snapshotApplicationService;

	public ParticipantOutcome join(UUID sessionId, String userId, UUID clientEventId) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));
		if (session.status() == SessionStatus.ENDED) {
			throw new SessionAlreadyEndedException(sessionId);
		}
		AppendResult result = eventAppendService.append(
				sessionId, userId, clientEventId,
				new EventPayload.ParticipantJoined(userId), null);
		if (!result.duplicate()) {
			sessionChannelPublisher.publish(SessionEventBroadcast.of(result.event()));
		}
		return new ParticipantOutcome(result.event(), result.duplicate());
	}

	public ParticipantOutcome leave(UUID sessionId, String userId, UUID clientEventId) {
		sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));
		// 이미 ENDED여도 leave는 허용한다 — 멤버십 정리(participant_left 영속 기록)는 늘 가능해야 한다.
		AppendResult result = eventAppendService.append(
				sessionId, userId, clientEventId,
				new EventPayload.ParticipantLeft(userId), null);
		if (!result.duplicate()) {
			sessionChannelPublisher.publish(SessionEventBroadcast.of(result.event()));
			snapshotApplicationService.snapshotAsync(sessionId);
		}
		return new ParticipantOutcome(result.event(), result.duplicate());
	}

	public record ParticipantOutcome(Event event, boolean duplicate) {
	}
}
