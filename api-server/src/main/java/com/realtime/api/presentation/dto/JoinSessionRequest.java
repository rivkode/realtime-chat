package com.realtime.api.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record JoinSessionRequest(
		@NotBlank String userId,
		@NotNull UUID clientEventId
) {
}
