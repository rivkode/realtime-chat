package com.realtime.chat.presentation;

import com.realtime.chat.application.ParticipantDispatchService;
import com.realtime.chat.application.ParticipantDispatchService.ParticipantOutcome;
import com.realtime.chat.presentation.dto.ParticipantAck;
import com.realtime.chat.presentation.dto.ParticipantActionRequest;
import com.realtime.common.domain.event.Event;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * 채팅 서버 join/leave STOMP 컨트롤러(설계서 §8.4).
 *
 * <p>destination:
 * <ul>
 *   <li>{@code SEND /app/sessions/{id}/join}  — 참여 의사 표명</li>
 *   <li>{@code SEND /app/sessions/{id}/leave} — 퇴장</li>
 * </ul>
 *
 * <p>ACK는 메시지 송신과 동일하게 {@code /user/queue/ack}로 송신자에게만 전달한다.
 */
@Controller
@RequiredArgsConstructor
public class StompParticipantController {

	private final ParticipantDispatchService participantDispatchService;
	private final SimpMessagingTemplate stompTemplate;

	@MessageMapping("/sessions/{id}/join")
	public void join(@DestinationVariable("id") UUID sessionId,
					 @Header(name = "simpSessionId") String connectionId,
					 @Payload ParticipantActionRequest request) {
		ParticipantOutcome outcome = participantDispatchService.join(
				sessionId, request.userId(), request.clientEventId());
		sendAck(connectionId, request.clientEventId(), outcome.event());
	}

	@MessageMapping("/sessions/{id}/leave")
	public void leave(@DestinationVariable("id") UUID sessionId,
					  @Header(name = "simpSessionId") String connectionId,
					  @Payload ParticipantActionRequest request) {
		ParticipantOutcome outcome = participantDispatchService.leave(
				sessionId, request.userId(), request.clientEventId());
		sendAck(connectionId, request.clientEventId(), outcome.event());
	}

	private void sendAck(String connectionId, UUID clientEventId, Event event) {
		stompTemplate.convertAndSendToUser(connectionId, "/queue/ack",
				new ParticipantAck(clientEventId, event.id(), event.type(), event.serverTs()));
	}
}
