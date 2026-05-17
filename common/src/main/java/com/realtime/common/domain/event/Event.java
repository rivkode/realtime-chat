package com.realtime.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 도메인 이벤트 — 진실의 원천 {@code events} 컬렉션의 한 행에 대응한다(설계서 §7.2).
 * 어노테이션 없음, 순수 Java 불변 record.
 *
 * <p>{@code id}는 서버가 발급한 UUIDv7로 정렬·복원·재연결 커서의 기준이다(§9.2).
 * {@code clientEventId}는 클라이언트 생성 멱등 키로 {@code {sessionId, clientEventId}} unique index가
 * 중복 INSERT를 차단한다(§9.1).
 */
public record Event(
		UUID id,
		UUID sessionId,
		EventType type,
		String actorUserId,
		UUID clientEventId,
		EventPayload payload,
		Instant clientTs,
		Instant serverTs
) {
	public Event {
		if (id == null) throw new IllegalArgumentException("id is required");
		if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
		if (type == null) throw new IllegalArgumentException("type is required");
		if (payload == null) throw new IllegalArgumentException("payload is required");
		if (payload.type() != type) {
			throw new IllegalArgumentException(
					"payload type mismatch: declared=" + type + ", payload=" + payload.type());
		}
		if (serverTs == null) throw new IllegalArgumentException("serverTs is required");
	}
}
