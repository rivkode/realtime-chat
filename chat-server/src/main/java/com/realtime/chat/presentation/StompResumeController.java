package com.realtime.chat.presentation;

import com.realtime.chat.application.ResumeService;
import com.realtime.chat.application.ResumeService.ResumeResult;
import com.realtime.chat.presentation.dto.ResumeRequest;
import com.realtime.chat.presentation.dto.ResumeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * resume / 주기 sync STOMP 컨트롤러(설계서 §9.3, §8.4).
 *
 * <p>{@code SEND /app/sessions/{id}/resume} {@code { lastEventId, limit? }}
 * 응답은 {@code /user/queue/resume}으로 송신자에게만 전달. 두 트리거(재연결 / 주기 sync)가
 * 같은 destination을 공유한다 — 서버 로직 동일.
 */
@Controller
@RequiredArgsConstructor
public class StompResumeController {

	private final ResumeService resumeService;
	private final SimpMessagingTemplate stompTemplate;

	@MessageMapping("/sessions/{id}/resume")
	public void resume(@DestinationVariable("id") UUID sessionId,
					   @Header(name = "simpSessionId") String connectionId,
					   @Payload ResumeRequest request) {
		ResumeResult result = resumeService.resume(sessionId, request.lastEventId(), request.limit());
		stompTemplate.convertAndSendToUser(connectionId, "/queue/resume", ResumeResponse.of(result));
	}
}
