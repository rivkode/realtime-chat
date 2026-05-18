package com.realtime.api.presentation.dto;

import com.realtime.api.application.TimelineApplicationService.TimelineResult;
import com.realtime.common.domain.session.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 시점 복원 응답(설계서 §16).
 */
public record TimelineResponse(
		UUID sessionId,
		Instant at,
		Set<String> participants,
		List<TimelineMessageView> messages,
		SessionStatus sessionStatus
) {
	public static TimelineResponse of(TimelineResult result) {
		List<TimelineMessageView> messages = result.state().messages().entrySet().stream()
				.map(entry -> TimelineMessageView.of(entry.getKey(), entry.getValue()))
				.toList();
		return new TimelineResponse(
				result.sessionId(),
				result.at(),
				result.state().participants(),
				messages,
				result.state().status()
		);
	}
}
