package com.realtime.chat.infrastructure.stomp;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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
 * <p>STOMP 프로토콜 heartbeat — 두 timeout을 비대칭으로 둔다:
 * <ul>
 *   <li><b>server send {@code 10s}</b> — 서버가 매 10초 빈 frame을 client로 보내 keep-alive.
 *       client의 WebSocket idle도 함께 reset된다.</li>
 *   <li><b>server expects from client {@code 30s}</b> — 브라우저가 background tab에서
 *       {@code setInterval}을 throttle(Chrome 30~60s)할 때 client→server heartbeat이 늦어져도
 *       서버가 dead로 오판하지 않게 마진을 둔다.</li>
 * </ul>
 *
 * <p>{@link #createWebSocketContainer()}로 Tomcat WebSocket의 session idle timeout을 0(disabled)
 * 로 설정 — STOMP heartbeat이 keep-alive를 충분히 수행하므로 idle timeout이 끼어들 필요가 없다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

	private static final long SERVER_HEARTBEAT_INTERVAL_MS = 10_000L;
	private static final long CLIENT_HEARTBEAT_TIMEOUT_MS = 30_000L;

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
		registry.setApplicationDestinationPrefixes("/app");
		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[]{SERVER_HEARTBEAT_INTERVAL_MS, CLIENT_HEARTBEAT_TIMEOUT_MS})
				.setTaskScheduler(heartbeatScheduler());
		registry.setUserDestinationPrefix("/user");
	}

	/**
	 * Tomcat WebSocket session의 idle timeout을 0(disabled)로 설정. STOMP heartbeat이
	 * keep-alive를 담당하므로 별도 idle timer는 끼어들지 않아야 한다 — 두 timer가 부정합으로
	 * 끊기는 사례 방지.
	 */
	@Bean
	public ServletServerContainerFactoryBean createWebSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxSessionIdleTimeout(0L);
		container.setMaxTextMessageBufferSize(64 * 1024);
		container.setMaxBinaryMessageBufferSize(64 * 1024);
		return container;
	}

	private TaskScheduler heartbeatScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("stomp-heartbeat-");
		scheduler.initialize();
		return scheduler;
	}
}
