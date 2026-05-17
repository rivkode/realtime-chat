package com.realtime.api.presentation.dto;

import com.realtime.common.domain.event.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AppendEventRequest(
		@NotNull EventType type,
		@NotBlank String actorUserId,
		@NotNull UUID clientEventId,
		Map<String, Object> payload,
		Instant clientTs
) {
}
