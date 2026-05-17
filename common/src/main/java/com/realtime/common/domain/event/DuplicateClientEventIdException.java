package com.realtime.common.domain.event;

import java.util.UUID;

/**
 * {@code {sessionId, clientEventId}} unique index 충돌 시 발생(설계서 §9.1).
 * 호출 측은 이 예외를 잡아 {@link EventRepository#findByClientEventId}로 기존 이벤트를 조회해 ACK한다.
 */
public class DuplicateClientEventIdException extends RuntimeException {

	private final UUID sessionId;
	private final UUID clientEventId;

	public DuplicateClientEventIdException(UUID sessionId, UUID clientEventId) {
		super("Duplicate clientEventId: sessionId=" + sessionId + ", clientEventId=" + clientEventId);
		this.sessionId = sessionId;
		this.clientEventId = clientEventId;
	}

	public UUID sessionId() { return sessionId; }
	public UUID clientEventId() { return clientEventId; }
}
