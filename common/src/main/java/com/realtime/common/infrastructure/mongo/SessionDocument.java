package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.session.SessionStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document(collection = "sessions")
public class SessionDocument {

	@Id
	private UUID id;
	@Indexed
	private SessionStatus status;
	@Indexed
	private String createdBy;
	private Instant createdAt;
	private Instant endedAt;

	public SessionDocument() {
	}

	public SessionDocument(UUID id, SessionStatus status, String createdBy,
						   Instant createdAt, Instant endedAt) {
		this.id = id;
		this.status = status;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.endedAt = endedAt;
	}

	public UUID getId() { return id; }
	public SessionStatus getStatus() { return status; }
	public String getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getEndedAt() { return endedAt; }
}
