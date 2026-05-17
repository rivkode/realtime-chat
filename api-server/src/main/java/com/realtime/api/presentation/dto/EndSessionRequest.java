package com.realtime.api.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record EndSessionRequest(
		@NotBlank String endedBy,
		@NotNull UUID clientEventId
) {
}
