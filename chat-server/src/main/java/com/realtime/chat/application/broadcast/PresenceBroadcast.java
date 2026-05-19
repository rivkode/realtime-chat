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
 *
 * <p>{@code traceId}는 일관성 위해 동봉(§14.2). presence 변경을 일으킨 그 액션(join/leave)의
 * traceId와 같은 ID를 그대로 잇는다 — 한 사용자 행위가 만든 모든 부수효과를 하나의 trace로.
 */
public record PresenceBroadcast(
		UUID sessionId,
		String userId,
		PresenceStatus status,
		Instant changedAt,
		String traceId
) implements SessionChannelMessage {
}
