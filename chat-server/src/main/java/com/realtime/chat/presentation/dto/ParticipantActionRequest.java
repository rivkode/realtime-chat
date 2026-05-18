package com.realtime.chat.presentation.dto;

import java.util.UUID;

/** STOMP join/leave 페이로드(설계서 §8.4). */
public record ParticipantActionRequest(
		String userId,
		UUID clientEventId
) {
}
