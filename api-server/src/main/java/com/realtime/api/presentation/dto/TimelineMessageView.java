package com.realtime.api.presentation.dto;

import com.realtime.common.domain.session.SessionState;

import java.util.UUID;

public record TimelineMessageView(
		UUID eventId,
		String sender,
		String content,
		SessionState.MessageStatus status
) {
	public static TimelineMessageView of(UUID eventId, SessionState.MessageView view) {
		return new TimelineMessageView(eventId, view.sender(), view.content(), view.status());
	}
}
