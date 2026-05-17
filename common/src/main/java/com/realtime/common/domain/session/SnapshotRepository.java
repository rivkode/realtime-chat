package com.realtime.common.domain.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository {

	Snapshot save(Snapshot snapshot);

	/** 시점 복원 — {@code snapshotAt <= at}인 최신 스냅샷(설계서 §10.1). */
	Optional<Snapshot> findLatestBefore(UUID sessionId, Instant at);

	/** 현재 상태 기준 가장 최신 스냅샷. */
	Optional<Snapshot> findLatest(UUID sessionId);
}
