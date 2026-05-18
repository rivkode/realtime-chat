package com.realtime.chat.presentation.dto;

import com.realtime.common.domain.event.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * join/leave ACK. {@link MessageAck}과 별도 DTO로 둔 이유는 type 정보를 함께 줘서
 * 클라이언트가 어떤 액션의 ACK인지 구분할 수 있게 하기 위함.
 */
public record ParticipantAck(
		UUID clientEventId,
		UUID eventId,
		EventType type,
		Instant serverTs
) {
}
