package com.realtime.common.domain.session;

import java.util.UUID;

public class SessionAlreadyEndedException extends RuntimeException {

	private final UUID sessionId;

	public SessionAlreadyEndedException(UUID sessionId) {
		super("Session already ended: " + sessionId);
		this.sessionId = sessionId;
	}

	public UUID sessionId() { return sessionId; }
}
