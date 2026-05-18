package com.realtime.chat.presentation;

import com.realtime.chat.application.PresenceService;
import com.realtime.chat.presentation.dto.HeartbeatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * presence heartbeat STOMP 컨트롤러(설계서 §8.3).
 *
 * <p>{@code SEND /app/sessions/{id}/heartbeat} {@code { userId }} — TTL 갱신만 수행. 상태
 * transition 아니므로 publish 없음(§8.3: "TTL 갱신만, 상태 변경 없음").
 *
 * <p>클라이언트는 10초 주기로 보내며 Redis 키 TTL(기본 30초)이 만료 전에 갱신된다.
 * 갱신이 끊기면 자연히 키 부재 = OFFLINE 판정 — 수동 인지 경로(§8.3 비정상 단절).
 */
@Controller
@RequiredArgsConstructor
public class StompPresenceController {

	private final PresenceService presenceService;

	@MessageMapping("/sessions/{id}/heartbeat")
	public void heartbeat(@DestinationVariable("id") UUID sessionId,
						  @Payload HeartbeatRequest request) {
		presenceService.onHeartbeat(sessionId, request.userId());
	}
}
