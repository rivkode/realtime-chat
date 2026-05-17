package com.realtime.common.domain.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

	Session save(Session session);

	Optional<Session> findById(UUID id);

	List<Session> findByFilter(SessionStatus status, String participantUserId,
							   Instant from, Instant to, UUID cursorId, int limit);
}
