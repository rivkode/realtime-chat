package com.realtime.chat.infrastructure.redis;

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
 * <p>Redis 장애 시 graceful degradation: 예외를 잡아 로그만 남기고 호출자에게 던지지 않는다.
 * presence는 정합성에 관여하지 않는 순수 표시용이라 데이터 손실 영향이 0(§8.3 마지막 단락, §15.4).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PresenceStore {

	private static final String VALUE = "online";

	private final RedisTemplate<String, Object> redisTemplate;

	@Value("${presence.ttl-seconds:30}")
	private long ttlSeconds;

	public void markOnline(UUID sessionId, String userId) {
		String key = key(sessionId, userId);
		try {
			redisTemplate.opsForValue().set(key, VALUE, Duration.ofSeconds(ttlSeconds));
		} catch (Exception ex) {
			log.warn("Presence SET failed for {}: {}", key, ex.getMessage());
		}
	}

	public void markOffline(UUID sessionId, String userId) {
		String key = key(sessionId, userId);
		try {
			redisTemplate.delete(key);
		} catch (Exception ex) {
			log.warn("Presence DEL failed for {}: {}", key, ex.getMessage());
		}
	}

	public boolean isOnline(UUID sessionId, String userId) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId, userId)));
		} catch (Exception ex) {
			log.warn("Presence read failed: {}", ex.getMessage());
			return false;
		}
	}

	private String key(UUID sessionId, String userId) {
		return "presence:" + sessionId + ":" + userId;
	}
}
