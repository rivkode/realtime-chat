package com.realtime.common.domain.session;

import java.util.UUID;

public class SessionNotFoundException extends RuntimeException {

	private final UUID sessionId;

	public SessionNotFoundException(UUID sessionId) {
		super("Session not found: " + sessionId);
		this.sessionId = sessionId;
	}

	public UUID sessionId() { return sessionId; }
}
