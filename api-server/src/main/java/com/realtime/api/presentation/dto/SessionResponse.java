package com.realtime.api.presentation.dto;

import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 세션 응답 DTO(설계서 §16).
 * {@code participants}는 events 집계로 채운다(별도 읽기 모델 없음, D1).
 */
public record SessionResponse(
		UUID id,
		SessionStatus status,
		String createdBy,
		List<String> participants,
		Instant createdAt,
		Instant endedAt
) {
	public static SessionResponse of(Session session, Set<String> participants) {
		return new SessionResponse(
				session.id(),
				session.status(),
				session.createdBy(),
				participants == null ? List.of() : List.copyOf(participants),
				session.createdAt(),
				session.endedAt()
		);
	}
}
