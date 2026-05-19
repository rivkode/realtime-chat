package com.realtime.chat.infrastructure.stomp;

import com.realtime.common.logging.TraceMdcKeys;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 메시지 처리 시 MDC에 {@code connectionId/sessionId/userId/traceId}를 박는다(§14.1, §14.2).
 *
 * <p><strong>왜 {@link ExecutorChannelInterceptor}인가</strong> — Spring의
 * {@code ExecutorSubscribableChannel}은 {@code preSend}를 send를 호출한 thread에서, 핸들러는
 * 별도 executor thread에서 실행한다. 핸들러(={@code @MessageMapping} 메서드 + 그 안의
 * {@code EventAppendService})가 실제로 도는 thread는 calling thread와 다르므로 {@code preSend}에서
 * 넣은 MDC가 보이지 않는다. {@code beforeHandle}/{@code afterMessageHandled}는 <strong>핸들러
 * thread에서</strong> 호출되므로 MDC가 그 thread로 정확히 전파된다.
 *
 * <p>traceId 발급 정책(§14.2):
 * <ul>
 *   <li>클라이언트가 {@code X-Trace-Id} 헤더를 보내면 그것을 잇는다</li>
 *   <li>없으면 새 UUID 발급</li>
 *   <li>매 STOMP 메시지마다 다른 trace</li>
 * </ul>
 */
@Component
public class StompMdcChannelInterceptor implements ExecutorChannelInterceptor {

	private static final Pattern SESSION_ID_IN_DEST = Pattern.compile("/sessions/([0-9a-fA-F-]{36})");

	@Override
	public Message<?> beforeHandle(@NonNull Message<?> message, @NonNull MessageChannel channel,
								   @NonNull MessageHandler handler) {
		StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (a == null) return message;

		String connId = a.getSessionId();
		if (connId != null) MDC.put(TraceMdcKeys.CONNECTION_ID, connId);

		String sid = extractSessionId(a.getDestination());
		if (sid != null) MDC.put(TraceMdcKeys.SESSION_ID, sid);

		String userId = a.getFirstNativeHeader("X-User-Id");
		if (userId != null && !userId.isBlank()) {
			MDC.put(TraceMdcKeys.USER_ID, userId);
		}

		String traceId = a.getFirstNativeHeader("X-Trace-Id");
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		MDC.put(TraceMdcKeys.TRACE_ID, traceId);

		return message;
	}

	@Override
	public void afterMessageHandled(@NonNull Message<?> message, @NonNull MessageChannel channel,
									@NonNull MessageHandler handler, Exception ex) {
		MDC.remove(TraceMdcKeys.CONNECTION_ID);
		MDC.remove(TraceMdcKeys.SESSION_ID);
		MDC.remove(TraceMdcKeys.USER_ID);
		MDC.remove(TraceMdcKeys.TRACE_ID);
	}

	private String extractSessionId(String destination) {
		if (destination == null) return null;
		Matcher m = SESSION_ID_IN_DEST.matcher(destination);
		return m.find() ? m.group(1) : null;
	}
}
