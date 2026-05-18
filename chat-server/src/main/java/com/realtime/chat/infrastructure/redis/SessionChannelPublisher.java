package com.realtime.chat.infrastructure.redis;

import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 세션 채널 발행자(설계서 §5.3, §8.1).
 *
 * <p>{@code PUBLISH channel:session:{sessionId}}. 실패해도 메시지 저장은 이미 끝난 상태(§9.3)이고
 * 수신측은 Pull 복구로 누락을 메우므로 best-effort. 발행 예외는 잡아 로그만 남기고 호출자에게
 * 던지지 않는다 — 채팅 서버가 Redis 장애 때문에 함께 죽으면 안 된다(§15.4 graceful degradation).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SessionChannelPublisher {

	private final RedisTemplate<String, Object> redisTemplate;

	public void publish(SessionEventBroadcast broadcast) {
		String channel = SessionChannels.of(broadcast.sessionId());
		try {
			redisTemplate.convertAndSend(channel, broadcast);
		} catch (Exception ex) {
			log.warn("Redis publish failed on {}: {}", channel, ex.getMessage());
		}
	}
}
