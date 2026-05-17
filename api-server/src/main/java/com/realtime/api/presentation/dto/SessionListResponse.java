package com.realtime.api.presentation.dto;

import java.util.List;
import java.util.UUID;

public record SessionListResponse(
		List<SessionResponse> sessions,
		UUID nextCursor
) {
}
