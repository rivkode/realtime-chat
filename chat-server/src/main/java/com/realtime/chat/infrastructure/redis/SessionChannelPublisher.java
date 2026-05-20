package com.realtime.chat.infrastructure.redis;

import com.realtime.chat.application.broadcast.SessionChannelMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 세션 채널 발행자(설계서 §5.3, §8.1, §8.3).
 *
 * <p>{@code PUBLISH channel:session:{sessionId}}. SessionChannelMessage sealed 인터페이스를 받아
 * 이벤트(SessionEventBroadcast)와 presence(PresenceBroadcast) 모두 같은 채널로 통합 전송.
 *
 * <p>실패해도 메시지 저장은 이미 끝난 상태(§9.3)이고 수신측은 Pull 복구로 누락을 메우므로 best-effort.
 * 발행 예외는 fallback에서 로그만 남기고 호출자에게 던지지 않는다 — 채팅 서버가 Redis 장애 때문에
 * 함께 죽으면 안 된다(§15.4 graceful degradation).
 *
 * <p>{@link CircuitBreaker} {@code "redis-publish"}로 감싸 연속 실패 시 OPEN으로 전환 — OPEN 상태
 * 에서는 호출 자체를 차단해 죽어가는 Redis에 매 요청 연결 시도가 쌓이는 것을 막는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SessionChannelPublisher {

	private final RedisTemplate<String, Object> redisTemplate;

	@CircuitBreaker(name = "redis-publish", fallbackMethod = "publishFallback")
	public void publish(SessionChannelMessage message) {
		String channel = SessionChannels.of(message.sessionId());
		redisTemplate.convertAndSend(channel, message);
	}

	/** Circuit breaker 호출 — Redis 장애 시 graceful degradation. 호출자에게 예외 던지지 않음. */
	@SuppressWarnings("unused")  // 시그니처는 원본 + Throwable. 리플렉션 호출.
	private void publishFallback(SessionChannelMessage message, Throwable ex) {
		log.warn("Redis publish skipped (circuit breaker or call failure): channel={}, kind={}, cause={}",
				SessionChannels.of(message.sessionId()), message.getClass().getSimpleName(), ex.getMessage());
	}
}
