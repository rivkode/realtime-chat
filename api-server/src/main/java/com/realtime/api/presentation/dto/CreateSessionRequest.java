package com.realtime.api.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
		@NotBlank String createdBy
) {
}
