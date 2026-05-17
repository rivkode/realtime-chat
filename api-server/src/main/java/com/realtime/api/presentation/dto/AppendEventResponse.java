package com.realtime.api.presentation.dto;

import java.time.Instant;
import java.util.UUID;

public record AppendEventResponse(
		UUID eventId,
		Instant serverTs
) {
}
