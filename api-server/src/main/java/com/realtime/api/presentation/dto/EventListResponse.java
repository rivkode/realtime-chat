package com.realtime.api.presentation.dto;

import java.util.List;
import java.util.UUID;

public record EventListResponse(
		List<EventView> events,
		UUID nextCursor
) {
}
