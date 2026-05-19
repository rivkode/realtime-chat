package com.realtime.chat.application.broadcast;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Pub/Sub 및 STOMP {@code /topic/sessions/{id}} push의 wire format(설계서 §5.3).
 *
 * <p>도메인 {@link Event}를 그대로 직렬화하지 않고 평탄 record로 변환하는 이유는 (1) payload가
 * sealed라 polymorphic 직렬화 설정이 번거롭고, (2) wire는 시간이 지나면서 도메인과 독립적으로
 * 진화할 수 있으므로 분리해두는 게 안전하기 때문이다.
 *
 * <p>{@code traceId}는 §14.2 추적 ID — Redis Pub/Sub은 헤더 개념이 없어 payload에 동봉하는 게
 * 유일한 전파 수단이다. 수신 측 chat-server가 이 필드를 MDC에 복원해 그 push 단계까지 같은 ID로
 * 묶는다.
 */
public record SessionEventBroadcast(
		UUID eventId,
		UUID sessionId,
		EventType type,
		String actorUserId,
		Map<String, Object> payload,
		Instant serverTs,
		String traceId
) implements SessionChannelMessage {

	public static SessionEventBroadcast of(Event event) {
		return new SessionEventBroadcast(
				event.id(),
				event.sessionId(),
				event.type(),
				event.actorUserId(),
				event.payload().toMap(),
				event.serverTs(),
				event.traceId()
		);
	}
}
