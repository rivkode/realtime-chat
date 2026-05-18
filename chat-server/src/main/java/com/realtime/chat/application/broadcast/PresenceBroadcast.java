package com.realtime.chat.application.broadcast;

import java.time.Instant;
import java.util.UUID;

/**
 * presence 변경 알림(설계서 §8.3).
 *
 * <p>세션 채널 {@code channel:session:{id}}로 흘러 상대에게 도착하며, events 컬렉션에는 영속되지
 * 않는다(connect/disconnect는 휘발성이라 §6.2 영속 이벤트 카탈로그에서 제외됨).
 *
 * <p>능동 전파 시점:
 * <ul>
 *   <li>접속(join)/명시적 leave: 그 서버가 publish</li>
 *   <li>비정상 단절: <em>능동 전파 없음</em> — TTL 만료로 자연히 키 부재가 되며, 상대 폴링·재진입 시 인지</li>
 * </ul>
 */
public record PresenceBroadcast(
		UUID sessionId,
		String userId,
		PresenceStatus status,
		Instant changedAt
) implements SessionChannelMessage {
}
