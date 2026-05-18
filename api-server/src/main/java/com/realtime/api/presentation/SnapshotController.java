package com.realtime.api.presentation;

import com.realtime.common.application.SnapshotApplicationService;
import com.realtime.api.presentation.dto.SnapshotResponse;
import com.realtime.common.domain.session.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * 스냅샷 수동 트리거(설계서 §16). 평상시엔 {@code SnapshotScheduler}와 {@code session_ended}
 * 이벤트 트리거가 자동 생성하며, 이 엔드포인트는 테스트·디버깅·운영자 수동 개입 용도.
 *
 * <ul>
 *   <li>새 이벤트가 있어 스냅샷을 새로 만들면 {@code 201 Created} + body</li>
 *   <li>기존 스냅샷 이후 이벤트가 없어 만들 필요 없으면 {@code 204 No Content}</li>
 * </ul>
 */
@RestController
@RequestMapping("/sessions/{id}/snapshots")
@RequiredArgsConstructor
public class SnapshotController {

	private final SnapshotApplicationService snapshotApplicationService;

	@PostMapping
	public ResponseEntity<SnapshotResponse> trigger(@PathVariable("id") UUID sessionId) {
		Optional<Snapshot> created = snapshotApplicationService.snapshotNow(sessionId);
		return created
				.map(s -> ResponseEntity.status(201).body(SnapshotResponse.of(s)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}
}
