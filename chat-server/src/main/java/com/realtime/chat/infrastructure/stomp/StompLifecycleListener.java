package com.realtime.chat.infrastructure.stomp;

import com.realtime.chat.infrastructure.redis.SessionChannelSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 연결 라이프사이클 → ConnectionRegistry / Redis 채널 구독 연동(설계서 §8.5).
 *
 * <p>흐름:
 * <ul>
 *   <li>{@code SUBSCRIBE /topic/sessions/{id}} 시 → registry 등록, 첫 연결이면 Redis 채널 구독</li>
 *   <li>{@code DISCONNECT} 시 → registry 해제, 마지막 연결이면 Redis 채널 구독 해제</li>
 *   <li>{@code UNSUBSCRIBE}는 명시적 해지 — 일반적으로 DISCONNECT가 같이 일어나므로 추가 처리는 같음</li>
 * </ul>
 *
 * <p>설계서의 "DISCONNECT는 멤버십·presence를 즉시 바꾸지 않는다" 원칙을 지킨다 — 여기선
 * 인메모리 연결 테이블 + Redis 채널 구독만 정리하고 {@code participant_left} 이벤트는
 * 명시적 leave일 때만 생성한다(후속 PR).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StompLifecycleListener {

	private static final Pattern SESSION_TOPIC = Pattern.compile("^/topic/sessions/([0-9a-fA-F-]{36})$");

	private final ConnectionRegistry connectionRegistry;
	private final SessionChannelSubscriber sessionChannelSubscriber;

	@EventListener
	public void onSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		UUID sessionId = parseSessionId(accessor.getDestination());
		String connectionId = accessor.getSessionId();
		if (sessionId == null || connectionId == null) return;

		connectionRegistry.register(sessionId, connectionId, sessionChannelSubscriber::subscribe);
		log.debug("STOMP subscribe: connection={}, session={}", connectionId, sessionId);
	}

	@EventListener
	public void onUnsubscribe(SessionUnsubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		String connectionId = accessor.getSessionId();
		if (connectionId == null) return;
		connectionRegistry.unregister(connectionId, sessionChannelSubscriber::unsubscribe);
	}

	@EventListener
	public void onDisconnect(SessionDisconnectEvent event) {
		String connectionId = event.getSessionId();
		if (connectionId == null) return;
		connectionRegistry.unregister(connectionId, sessionChannelSubscriber::unsubscribe);
		log.debug("STOMP disconnect: connection={}", connectionId);
	}

	private UUID parseSessionId(String destination) {
		if (destination == null) return null;
		Matcher matcher = SESSION_TOPIC.matcher(destination);
		if (!matcher.matches()) return null;
		try {
			return UUID.fromString(matcher.group(1));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
