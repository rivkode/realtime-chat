package com.realtime.common.domain.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 복원 출발점 체크포인트(설계서 §7.2, §10).
 * {@code upToEventId}까지의 이벤트가 반영된 상태({@code state})를 담는다.
 */
public record Snapshot(
		UUID id,
		UUID sessionId,
		UUID upToEventId,
		SessionState state,
		Instant snapshotAt
) {
	public Snapshot {
		if (id == null) throw new IllegalArgumentException("id is required");
		if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
		if (upToEventId == null) throw new IllegalArgumentException("upToEventId is required");
		if (state == null) throw new IllegalArgumentException("state is required");
		if (snapshotAt == null) throw new IllegalArgumentException("snapshotAt is required");
	}
}
