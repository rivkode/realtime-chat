package com.realtime.chat.infrastructure.stomp;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP/WebSocket 설정(설계서 §5·§8.4).
 *
 * <p>destination 약속(§8.4):
 * <ul>
 *   <li>클라이언트 → 서버: {@code SEND /app/sessions/{id}/messages}</li>
 *   <li>서버 → 클라이언트: {@code SUBSCRIBE /topic/sessions/{id}} (라이브 이벤트 스트림)</li>
 *   <li>서버 → 송신자: {@code SEND /user/queue/ack} (멱등 ACK)</li>
 * </ul>
 *
 * <p>STOMP 프로토콜 heartbeat 활성화 — presence(§8.3 후속 PR)와 연결 생사 판정에 사용.
 * 우리는 simple broker만 쓰므로 외부 메시지 브로커는 두지 않는다 — Redis Pub/Sub은
 * STOMP broker 밖에서 별도로 흐른다(§5.3).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

	private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

	private final SimpSessionUserChannelInterceptor simpSessionUserChannelInterceptor;

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(simpSessionUserChannelInterceptor);
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// 클라이언트 → 서버 메시지 prefix
		registry.setApplicationDestinationPrefixes("/app");
		// 서버 → 클라이언트 broadcast/유저 destination prefix
		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[]{HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
				.setTaskScheduler(heartbeatScheduler());
		// /user/queue/... destination prefix
		registry.setUserDestinationPrefix("/user");
	}

	private TaskScheduler heartbeatScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("stomp-heartbeat-");
		scheduler.initialize();
		return scheduler;
	}
}
