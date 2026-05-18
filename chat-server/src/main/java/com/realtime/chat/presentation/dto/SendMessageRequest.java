package com.realtime.chat.presentation.dto;

import java.util.UUID;

/**
 * STOMP {@code SEND /app/sessions/{id}/messages}의 페이로드(설계서 §8.4).
 */
public record SendMessageRequest(
		UUID clientEventId,
		String content
) {
}
