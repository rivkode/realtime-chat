package com.realtime.chat.presentation;

import com.realtime.chat.application.MessageDispatchService;
import com.realtime.chat.application.MessageDispatchService.DispatchResult;
import com.realtime.chat.presentation.dto.MessageAck;
import com.realtime.chat.presentation.dto.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * STOMP 메시지 수집(설계서 §8.4).
 *
 * <p>destination: {@code /app/sessions/{id}/messages}.
 * 인증이 본 프로젝트 범위 밖이므로(설계서 §17, §16 모두 가정 명시) 송신자 식별은 STOMP CONNECT
 * 헤더 {@code X-User-Id}로 받는다 — 임시 단순 토큰 가정.
 *
 * <p>ACK는 {@code /user/queue/ack} destination으로 송신자에게만 전달.
 * Spring의 user destination resolver는 STOMP 세션의 Principal을 기준으로 라우팅하므로,
 * 본 PR에서는 connection 단위로 직접 destination을 지정하는 단순 경로를 사용한다 — 인증/Principal
 * wiring은 후속 PR(인증 도입 시점).
 */
@Controller
@RequiredArgsConstructor
public class StompMessageController {

	private final MessageDispatchService messageDispatchService;
	private final SimpMessagingTemplate stompTemplate;

	@MessageMapping("/sessions/{id}/messages")
	public void send(@DestinationVariable("id") UUID sessionId,
					 @Header(name = "X-User-Id", required = false) String userId,
					 @Header(name = "simpSessionId") String connectionId,
					 @Payload SendMessageRequest request) {
		String actor = userId != null ? userId : "anonymous";
		DispatchResult result = messageDispatchService.dispatch(
				sessionId, actor, request.clientEventId(), request.content());

		stompTemplate.convertAndSendToUser(connectionId, "/queue/ack",
				new MessageAck(request.clientEventId(), result.event().id(), result.event().serverTs()));
	}
}
