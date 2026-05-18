package com.realtime.api.presentation.dto;

import com.realtime.common.domain.session.Snapshot;

import java.time.Instant;
import java.util.UUID;

public record SnapshotResponse(
		UUID snapshotId,
		UUID upToEventId,
		Instant snapshotAt
) {
	public static SnapshotResponse of(Snapshot snapshot) {
		return new SnapshotResponse(snapshot.id(), snapshot.upToEventId(), snapshot.snapshotAt());
	}
}
