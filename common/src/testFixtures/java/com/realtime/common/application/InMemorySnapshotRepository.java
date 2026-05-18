package com.realtime.common.application;

import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class InMemorySnapshotRepository implements SnapshotRepository {

	private final List<Snapshot> store = new ArrayList<>();

	@Override
	public synchronized Snapshot save(Snapshot snapshot) {
		store.add(snapshot);
		return snapshot;
	}

	@Override
	public Optional<Snapshot> findLatestBefore(UUID sessionId, Instant at) {
		return store.stream()
				.filter(s -> s.sessionId().equals(sessionId))
				.filter(s -> !s.snapshotAt().isAfter(at))
				.max(Comparator.comparing(Snapshot::snapshotAt));
	}

	@Override
	public Optional<Snapshot> findLatest(UUID sessionId) {
		return store.stream()
				.filter(s -> s.sessionId().equals(sessionId))
				.max(Comparator.comparing(Snapshot::snapshotAt));
	}
}
