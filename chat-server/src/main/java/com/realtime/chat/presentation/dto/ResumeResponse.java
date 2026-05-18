package com.realtime.chat.presentation.dto;

import com.realtime.chat.application.ResumeService.Mode;
import com.realtime.chat.application.ResumeService.ResumeResult;
import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * resume / 주기 sync 응답(설계서 §9.3).
 *
 * <ul>
 *   <li>{@link Mode#INCREMENTAL}: {@code events}에 catch-up 이벤트, {@code state}는 null</li>
 *   <li>{@link Mode#SNAPSHOT}: {@code state}에 현재 상태, {@code events}는 비어 있음</li>
 * </ul>
 *
 * <p>두 경우 모두 {@code lastEventId}를 새 기준점으로 클라이언트가 저장 — 다음 sync의 입력이 된다.
 */
public record ResumeResponse(
		Mode mode,
		List<SessionEventBroadcast> events,
		State state,
		UUID lastEventId,
		boolean hasMore
) {
	public static ResumeResponse of(ResumeResult result) {
		List<SessionEventBroadcast> wireEvents = result.events().stream()
				.map(SessionEventBroadcast::of)
				.toList();
		State stateView = result.state() == null ? null : State.of(result.state());
		return new ResumeResponse(result.mode(), wireEvents, stateView, result.lastEventId(), result.hasMore());
	}

	public record State(
			List<String> participants,
			List<MessageView> messages,
			SessionStatus status
	) {
		public static State of(SessionState state) {
			List<MessageView> messages = state.messages().entrySet().stream()
					.map(entry -> new MessageView(
							entry.getKey(),
							entry.getValue().sender(),
							entry.getValue().content(),
							entry.getValue().status().name()))
					.toList();
			return new State(List.copyOf(state.participants()), messages, state.status());
		}
	}

	public record MessageView(UUID eventId, String sender, String content, String status) {
	}
}
