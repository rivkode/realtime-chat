package com.realtime.chat.presentation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP {@code SEND /user/queue/ack}로 송신자에게 돌려주는 멱등 ACK(설계서 §8.4, §9.1).
 *
 * <p>같은 {@code clientEventId} 재전송이라도 같은 {@code eventId}/{@code serverTs}를 반환해
 * 클라이언트가 분실로 오해하지 않도록 한다.
 */
public record MessageAck(
		UUID clientEventId,
		UUID eventId,
		Instant serverTs
) {
}
