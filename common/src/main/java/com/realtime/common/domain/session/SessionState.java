package com.realtime.common.domain.session;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 리듀서 fold 결과로 만들어지는 세션의 시점 상태(설계서 §10).
 * 스냅샷의 {@code state}로도 저장된다. 불변 보장은 외부에서 다루기 나름.
 */
public final class SessionState {

	private final Set<String> participants;
	private final Map<UUID, MessageView> messages;
	private SessionStatus status;

	private SessionState(Set<String> participants, Map<UUID, MessageView> messages, SessionStatus status) {
		this.participants = participants;
		this.messages = messages;
		this.status = status;
	}

	public static SessionState empty() {
		return new SessionState(new LinkedHashSet<>(), new LinkedHashMap<>(), SessionStatus.ACTIVE);
	}

	public Set<String> participants() { return participants; }
	public Map<UUID, MessageView> messages() { return messages; }
	public SessionStatus status() { return status; }
	public void status(SessionStatus status) { this.status = status; }

	public record MessageView(String sender, String content, MessageStatus status) {
		public MessageView withContent(String content, MessageStatus status) {
			return new MessageView(sender, content, status);
		}
	}

	public enum MessageStatus {
		SENT, EDITED, DELETED
	}
}
