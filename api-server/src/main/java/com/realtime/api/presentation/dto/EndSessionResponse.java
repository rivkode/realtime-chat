package com.realtime.api.presentation.dto;

import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionStatus;

import java.time.Instant;
import java.util.UUID;

public record EndSessionResponse(
		UUID id,
		SessionStatus status,
		Instant endedAt
) {
	public static EndSessionResponse of(Session session) {
		return new EndSessionResponse(session.id(), session.status(), session.endedAt());
	}
}
