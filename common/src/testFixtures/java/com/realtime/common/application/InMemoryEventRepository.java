package com.realtime.common.application;

import com.realtime.common.domain.event.DuplicateClientEventIdException;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.event.EventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트용 인메모리 EventRepository. {@code (sessionId, clientEventId)} unique를 흉내 낸다. */
public class InMemoryEventRepository implements EventRepository {

	private final List<Event> events = new ArrayList<>();
	private final Map<String, UUID> dedupIndex = new ConcurrentHashMap<>();

	@Override
	public synchronized Event append(Event event) {
		String key = event.sessionId() + ":" + event.clientEventId();
		if (dedupIndex.containsKey(key)) {
			throw new DuplicateClientEventIdException(event.sessionId(), event.clientEventId());
		}
		dedupIndex.put(key, event.id());
		events.add(event);
		return event;
	}

	@Override
	public Optional<Event> findByClientEventId(UUID sessionId, UUID clientEventId) {
		return events.stream()
				.filter(e -> e.sessionId().equals(sessionId) && e.clientEventId().equals(clientEventId))
				.findFirst();
	}

	@Override
	public List<Event> findAfter(UUID sessionId, UUID lastEventId, int limit) {
		return events.stream()
				.filter(e -> e.sessionId().equals(sessionId))
				.filter(e -> lastEventId == null || e.id().compareTo(lastEventId) > 0)
				.sorted(Comparator.comparing(Event::id))
				.limit(limit)
				.toList();
	}

	@Override
	public List<Event> findForReplay(UUID sessionId, UUID afterEventIdExclusive, Instant atInclusive) {
		return events.stream()
				.filter(e -> e.sessionId().equals(sessionId))
				.filter(e -> afterEventIdExclusive == null || e.id().compareTo(afterEventIdExclusive) > 0)
				.filter(e -> atInclusive == null || !e.serverTs().isAfter(atInclusive))
				.sorted(Comparator.comparing(Event::id))
				.toList();
	}

	@Override
	public List<Event> findRecentMessages(UUID sessionId, int limit) {
		return events.stream()
				.filter(e -> e.sessionId().equals(sessionId))
				.filter(e -> e.type() == EventType.MESSAGE_SENT)
				.sorted(Comparator.comparing(Event::id).reversed())
				.limit(limit)
				.toList();
	}

	@Override
	public long countAfter(UUID sessionId, UUID lastSnapshotEventId) {
		return events.stream()
				.filter(e -> e.sessionId().equals(sessionId))
				.filter(e -> lastSnapshotEventId == null || e.id().compareTo(lastSnapshotEventId) > 0)
				.count();
	}

	@Override
	public Set<String> findJoinedUserIds(UUID sessionId) {
		Set<String> result = new LinkedHashSet<>();
		events.stream()
				.filter(e -> e.sessionId().equals(sessionId))
				.filter(e -> e.type() == EventType.PARTICIPANT_JOINED)
				.forEach(e -> result.add(e.actorUserId()));
		return result;
	}
}
