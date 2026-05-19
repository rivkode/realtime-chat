package com.realtime.chat.infrastructure.stomp;

import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 메시지 처리 시 MDC에 {@code connectionId}, {@code sessionId}, {@code userId}를 박는다(§14.1).
 *
 * <p>{@code preSend}에서 채워 application 컨트롤러·리스너 로그에 자동 첨부되도록 하고,
 * {@code afterSendCompletion}에서 정리한다.
 *
 * <p>destination에서 sessionId 추출(/topic/sessions/UUID, /app/sessions/UUID/...).
 * userId는 STOMP CONNECT 헤더 {@code X-User-Id} 또는 SEND 헤더에서 추출.
 */
@Component
public class StompMdcChannelInterceptor implements ChannelInterceptor {

	private static final Pattern SESSION_ID_IN_DEST = Pattern.compile("/sessions/([0-9a-fA-F-]{36})");

	@Override
	public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
		StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (a == null) return message;

		String connId = a.getSessionId();
		if (connId != null) MDC.put("connectionId", connId);

		String sid = extractSessionId(a.getDestination());
		if (sid != null) MDC.put("sessionId", sid);

		String userId = a.getFirstNativeHeader("X-User-Id");
		if (userId != null && !userId.isBlank()) {
			MDC.put("userId", userId);
		}

		return message;
	}

	@Override
	public void afterSendCompletion(@NonNull Message<?> message, @NonNull MessageChannel channel,
									boolean sent, Exception ex) {
		// 별도 처리 단위로 격리 — 다음 메시지의 MDC 오염을 막는다.
		MDC.remove("connectionId");
		MDC.remove("sessionId");
		MDC.remove("userId");
	}

	private String extractSessionId(String destination) {
		if (destination == null) return null;
		Matcher m = SESSION_ID_IN_DEST.matcher(destination);
		return m.find() ? m.group(1) : null;
	}
}
