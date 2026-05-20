package com.realtime.chat.application;

import com.realtime.chat.application.broadcast.PresenceBroadcast;
import com.realtime.chat.application.broadcast.PresenceStatus;
import com.realtime.chat.infrastructure.redis.PresenceStore;
import com.realtime.chat.infrastructure.redis.SessionChannelPublisher;
import com.realtime.common.logging.TraceMdcKeys;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * presence 응용 서비스(설계서 §8.3).
 *
 * <p>저장(A: {@link PresenceStore})과 전파(B: {@link SessionChannelPublisher})를 명시적으로
 * 분리해 호출 — "키를 SET하는 것이 전파를 자동으로 일으키지 않는다"는 §8.3 원칙을 코드에 그대로 반영.
 *
 * <p>onJoin / onLeave는 상태 변경이므로 능동 전파한다. onHeartbeat은 TTL 갱신만 — 상태가
 * 안 바뀌었으므로 전파 없음.
 *
 * <p>{@code traceId}는 MDC에서 읽어 PresenceBroadcast에 동봉(§14.2) — 그 행위(join/leave)와 같은 trace.
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

	private final PresenceStore presenceStore;
	private final SessionChannelPublisher sessionChannelPublisher;
	private final Clock clock;

	public void onJoin(UUID sessionId, String userId) {
		presenceStore.markOnline(sessionId, userId);
		sessionChannelPublisher.publish(new PresenceBroadcast(
				sessionId, userId, PresenceStatus.ONLINE, clock.instant(), MDC.get(TraceMdcKeys.TRACE_ID)));
	}

	public void onLeave(UUID sessionId, String userId) {
		presenceStore.markOffline(sessionId, userId);
		sessionChannelPublisher.publish(new PresenceBroadcast(
				sessionId, userId, PresenceStatus.OFFLINE, clock.instant(), MDC.get(TraceMdcKeys.TRACE_ID)));
	}

	public void onHeartbeat(UUID sessionId, String userId) {
		// TTL 갱신만. 상태 transition 아니므로 publish 없음.
		presenceStore.markOnline(sessionId, userId);
	}
}
