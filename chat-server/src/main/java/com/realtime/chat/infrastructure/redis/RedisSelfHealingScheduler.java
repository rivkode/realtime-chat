package com.realtime.chat.infrastructure.redis;

import com.realtime.chat.infrastructure.stomp.ConnectionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Redis 채널 구독 self-healing(설계서 §15.4).
 *
 * <p>Spring Data Redis + Lettuce는 connection drop 시 자동 재연결을 시도하지만, 일부 환경에서
 * {@link org.springframework.data.redis.listener.RedisMessageListenerContainer}의 listener가
 * detach될 수 있다. 그 경우 새 메시지 publish는 받지 못한다.
 *
 * <p>안전망: 주기적으로 이 서버가 보유한 sessionId 전체에 대해 {@link SessionChannelSubscriber#subscribe}를
 * 다시 호출한다. {@code computeIfAbsent} 기반이라 이미 등록된 sessionId는 no-op — 멱등.
 * Redis가 복구된 직후엔 다음 주기에 끊긴 listener가 자연히 재등록된다.
 *
 * <p>주기를 너무 짧게 잡으면 CPU 낭비, 너무 길면 복구 지연 — 30초가 보수적 절충.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RedisSelfHealingScheduler {

	private final ConnectionRegistry connectionRegistry;
	private final SessionChannelSubscriber sessionChannelSubscriber;

	@Value("${redis.self-healing.enabled:true}")
	private boolean enabled;

	@Scheduled(fixedDelayString = "${redis.self-healing.interval-ms:30000}", initialDelay = 30_000L)
	public void resubscribeAll() {
		if (!enabled) return;
		Set<UUID> tracked = connectionRegistry.trackedSessionIds();
		if (tracked.isEmpty()) return;

		int reSubscribed = 0;
		for (UUID sessionId : tracked) {
			try {
				sessionChannelSubscriber.subscribe(sessionId);   // 멱등
				reSubscribed++;
			} catch (Exception ex) {
				log.warn("Self-healing re-subscribe failed for {}: {}", sessionId, ex.getMessage());
			}
		}
		log.debug("Redis self-healing tick — tracked={}, attempted={}", tracked.size(), reSubscribed);
	}
}
