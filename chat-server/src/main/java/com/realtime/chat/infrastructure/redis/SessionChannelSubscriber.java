package com.realtime.chat.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.application.broadcast.PresenceBroadcast;
import com.realtime.chat.application.broadcast.SessionChannelMessage;
import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import com.realtime.chat.infrastructure.metrics.ChatMetrics;
import com.realtime.common.logging.TraceMdcKeys;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 세션 채널 구독자(설계서 §5.3).
 *
 * <p>chat-server가 보유한 sessionId마다 Redis 채널을 동적 구독한다. 첫 연결 시
 * {@link #subscribe(UUID)}, 마지막 끊김 시 {@link #unsubscribe(UUID)} — {@link
 * com.realtime.chat.infrastructure.stomp.ConnectionRegistry}의 콜백으로 호출된다.
 *
 * <p>수신한 메시지는 그 sessionId의 STOMP destination {@code /topic/sessions/{id}}로 중계 →
 * SimpleBroker가 그 destination 구독자들에게 자동 push. 자기 서버가 발행한 메시지가 자기에게
 * 돌아와도 SimpleBroker 라우팅이 그대로 잘 동작 — 같은 세션 다른 디바이스도 같은 경로로 받음.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SessionChannelSubscriber {

	private final RedisMessageListenerContainer container;
	private final SimpMessagingTemplate stompTemplate;
	private final ObjectMapper redisObjectMapper;
	private final ChatMetrics metrics;

	/** sessionId → 등록된 (listener, topic) 쌍. 중복 구독 방지. */
	private final ConcurrentHashMap<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

	public void subscribe(UUID sessionId) {
		subscriptions.computeIfAbsent(sessionId, this::createSubscription);
	}

	public void unsubscribe(UUID sessionId) {
		Subscription removed = subscriptions.remove(sessionId);
		if (removed != null) {
			container.removeMessageListener(removed.listener, removed.topic);
		}
	}

	private Subscription createSubscription(UUID sessionId) {
		String channel = SessionChannels.of(sessionId);
		Topic topic = new ChannelTopic(channel);
		MessageListener listener = (message, pattern) -> handle(sessionId, message);
		container.addMessageListener(listener, topic);
		log.debug("Subscribed redis channel {}", channel);
		return new Subscription(listener, topic);
	}

	private void handle(UUID sessionId, Message message) {
		try {
			// SessionChannelMessage는 sealed + Jackson polymorphic이라 kind discriminator로
			// SessionEventBroadcast/PresenceBroadcast 자동 분기 deserialize.
			SessionChannelMessage decoded = redisObjectMapper.readValue(
					message.getBody(), SessionChannelMessage.class);
			// §14.2 — payload에 동봉된 traceId를 MDC에 복원해 이 push 단계 로그가 같은 trace로 묶이게.
			String traceId = extractTraceId(decoded);
			boolean traceSet = false;
			if (traceId != null) {
				MDC.put(TraceMdcKeys.TRACE_ID, traceId);
				traceSet = true;
			}
			try {
				stompTemplate.convertAndSend("/topic/sessions/" + sessionId, decoded);
				metrics.recordDelivered(true);  // §14.3 단계별 카운터 — 수신측 push 성공
			} finally {
				if (traceSet) MDC.remove(TraceMdcKeys.TRACE_ID);
			}
		} catch (Exception ex) {
			metrics.recordDelivered(false);
			log.warn("Failed to dispatch redis message on session {}: {}", sessionId, ex.getMessage());
		}
	}

	private String extractTraceId(SessionChannelMessage m) {
		return switch (m) {
			case SessionEventBroadcast e -> e.traceId();
			case PresenceBroadcast p -> p.traceId();
		};
	}

	private record Subscription(MessageListener listener, Topic topic) {
	}
}
