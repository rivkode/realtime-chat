package com.realtime.chat.infrastructure.redis;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * presence 저장(설계서 §8.3 동작 A).
 *
 * <pre>
 *   key   : presence:{sessionId}:{userId}
 *   value : "online"
 *   TTL   : presence.ttl-seconds (기본 30초 — heartbeat 주기 10초의 3배)
 * </pre>
 *
 * <p>이 컴포넌트는 키-값 저장만 담당한다. 전파(B)는 {@link SessionChannelPublisher}가 별도로
 * 수행 — "키를 SET하는 것이 전파를 자동으로 일으키지 않는다"(§8.3).
 *
 * <p>Redis 장애 시 graceful degradation: presence는 정합성에 관여하지 않는 순수 표시용이라
 * 데이터 손실 영향이 0(§8.3 마지막 단락, §15.4). {@link CircuitBreaker} {@code "redis-presence"}로
 * 감싸 연속 실패 시 OPEN — 죽어가는 Redis에 매 요청 연결 시도가 쌓이는 걸 방지.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PresenceStore {

	private static final String VALUE = "online";

	private final RedisTemplate<String, Object> redisTemplate;

	@Value("${presence.ttl-seconds:30}")
	private long ttlSeconds;

	@CircuitBreaker(name = "redis-presence", fallbackMethod = "markOnlineFallback")
	public void markOnline(UUID sessionId, String userId) {
		redisTemplate.opsForValue().set(key(sessionId, userId), VALUE, Duration.ofSeconds(ttlSeconds));
	}

	@CircuitBreaker(name = "redis-presence", fallbackMethod = "markOfflineFallback")
	public void markOffline(UUID sessionId, String userId) {
		redisTemplate.delete(key(sessionId, userId));
	}

	@CircuitBreaker(name = "redis-presence", fallbackMethod = "isOnlineFallback")
	public boolean isOnline(UUID sessionId, String userId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId, userId)));
	}

	@SuppressWarnings("unused")
	private void markOnlineFallback(UUID sessionId, String userId, Throwable ex) {
		log.warn("Presence SET skipped: {}:{} cause={}", sessionId, userId, ex.getMessage());
	}

	@SuppressWarnings("unused")
	private void markOfflineFallback(UUID sessionId, String userId, Throwable ex) {
		log.warn("Presence DEL skipped: {}:{} cause={}", sessionId, userId, ex.getMessage());
	}

	@SuppressWarnings("unused")
	private boolean isOnlineFallback(UUID sessionId, String userId, Throwable ex) {
		log.warn("Presence read skipped: {}:{} cause={}", sessionId, userId, ex.getMessage());
		return false;
	}

	private String key(UUID sessionId, String userId) {
		return "presence:" + sessionId + ":" + userId;
	}
}
