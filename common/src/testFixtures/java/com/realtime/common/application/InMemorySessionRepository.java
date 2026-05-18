package com.realtime.common.application;

import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionRepository implements SessionRepository {

	private final Map<UUID, Session> store = new ConcurrentHashMap<>();

	@Override
	public Session save(Session session) {
		store.put(session.id(), session);
		return session;
	}

	@Override
	public Optional<Session> findById(UUID id) {
		return Optional.ofNullable(store.get(id));
	}

	@Override
	public List<Session> findByFilter(SessionStatus status, String participantUserId,
									  Instant from, Instant to, UUID cursorId, int limit) {
		List<Session> list = new ArrayList<>(store.values());
		list.sort(Comparator.comparing(Session::id));
		return list.stream()
				.filter(s -> status == null || s.status() == status)
				.filter(s -> participantUserId == null || participantUserId.equals(s.createdBy()))
				.filter(s -> from == null || !s.createdAt().isBefore(from))
				.filter(s -> to == null || !s.createdAt().isAfter(to))
				.filter(s -> cursorId == null || s.id().compareTo(cursorId) > 0)
				.limit(limit)
				.toList();
	}
}
