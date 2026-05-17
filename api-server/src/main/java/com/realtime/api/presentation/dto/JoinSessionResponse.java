package com.realtime.api.presentation.dto;

import com.realtime.common.domain.session.SessionStatus;

import java.util.UUID;

public record JoinSessionResponse(
		UUID eventId,
		UUID sessionId,
		SessionStatus status
) {
}
