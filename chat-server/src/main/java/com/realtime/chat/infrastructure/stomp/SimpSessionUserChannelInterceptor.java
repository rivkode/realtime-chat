package com.realtime.chat.infrastructure.stomp;

import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP CONNECT 시 {@code simpSessionId}를 Principal로 설정한다.
 *
 * <p>{@code SimpMessagingTemplate#convertAndSendToUser(simpSessionId, ...)}가 동작하려면
 * 그 user가 {@code SimpUserRegistry}에 등록되어 있어야 한다. 등록은 Spring이 STOMP frame의
 * {@code user} 필드(Principal)를 보고 자동 수행하므로, CONNECT 시점에 Principal을
 * {@code simpSessionId}로 설정해 두면 ack/resume 응답이 {@code /user/queue/...}로 정상 라우팅된다.
 *
 * <p>인증·Spring Security 도입 시 이 wiring은 제거되고 Principal은 인증 결과로 결정된다.
 */
@Component
public class SimpSessionUserChannelInterceptor implements ChannelInterceptor {

	@Override
	public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
			String sessionId = accessor.getSessionId();
			if (sessionId != null && accessor.getUser() == null) {
				accessor.setUser(new SimpSessionPrincipal(sessionId));
			}
		}
		return message;
	}

	private record SimpSessionPrincipal(String name) implements Principal {
		@Override
		public String getName() {
			return name;
		}
	}
}
