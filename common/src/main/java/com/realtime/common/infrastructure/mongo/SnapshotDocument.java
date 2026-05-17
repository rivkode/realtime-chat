package com.realtime.common.infrastructure.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code snapshots} 컬렉션 영속 모델(설계서 §7.2).
 * {@code {sessionId, upToEventId}} unique — 같은 지점 중복 스냅샷 차단(§12.1 멱등 배치 근거).
 * {@code {sessionId, snapshotAt}} — {@code timeline?at=}에서 직전 스냅샷 조회(§10.1).
 */
@Document(collection = "snapshots")
@CompoundIndexes({
		@CompoundIndex(name = "idx_snapshot_session_upto_unique",
				def = "{'sessionId': 1, 'upToEventId': 1}", unique = true),
		@CompoundIndex(name = "idx_snapshot_session_at",
				def = "{'sessionId': 1, 'snapshotAt': -1}")
})
public class SnapshotDocument {

	@Id
	private UUID id;
	private UUID sessionId;
	private UUID upToEventId;
	private Map<String, Object> state;
	private Instant snapshotAt;

	public SnapshotDocument() {
	}

	public SnapshotDocument(UUID id, UUID sessionId, UUID upToEventId,
							Map<String, Object> state, Instant snapshotAt) {
		this.id = id;
		this.sessionId = sessionId;
		this.upToEventId = upToEventId;
		this.state = state;
		this.snapshotAt = snapshotAt;
	}

	public UUID getId() { return id; }
	public UUID getSessionId() { return sessionId; }
	public UUID getUpToEventId() { return upToEventId; }
	public Map<String, Object> getState() { return state; }
	public Instant getSnapshotAt() { return snapshotAt; }
}
