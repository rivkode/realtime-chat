package com.realtime.common.domain.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 세션 메타데이터(설계서 §7.2). 상태 변경은 의미 있는 메서드로만 가능(setter 노출 금지).
 */
public final class Session {

	private final UUID id;
	private final String createdBy;
	private final Instant createdAt;
	private SessionStatus status;
	private Instant endedAt;

	private Session(UUID id, String createdBy, Instant createdAt, SessionStatus status, Instant endedAt) {
		this.id = id;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.status = status;
		this.endedAt = endedAt;
	}

	public static Session create(UUID id, String createdBy, Instant createdAt) {
		if (id == null) throw new IllegalArgumentException("id is required");
		if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy is required");
		if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
		return new Session(id, createdBy, createdAt, SessionStatus.ACTIVE, null);
	}

	public static Session reconstitute(UUID id, String createdBy, Instant createdAt,
									   SessionStatus status, Instant endedAt) {
		return new Session(id, createdBy, createdAt, status, endedAt);
	}

	public void end(Instant endedAt) {
		if (this.status == SessionStatus.ENDED) {
			return;
		}
		if (endedAt == null) throw new IllegalArgumentException("endedAt is required");
		this.status = SessionStatus.ENDED;
		this.endedAt = endedAt;
	}

	public void markInterrupted() {
		if (this.status == SessionStatus.ACTIVE) {
			this.status = SessionStatus.INTERRUPTED;
		}
	}

	public UUID id() { return id; }
	public String createdBy() { return createdBy; }
	public Instant createdAt() { return createdAt; }
	public SessionStatus status() { return status; }
	public Instant endedAt() { return endedAt; }
}
