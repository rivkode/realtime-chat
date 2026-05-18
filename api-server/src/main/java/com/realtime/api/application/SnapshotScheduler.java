package com.realtime.api.application;

import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionStatus;
import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 스냅샷 배치 트리거(설계서 §12.3 trigger 2).
 *
 * <p>1시간 간격으로 active 세션을 훑되, <strong>마지막 스냅샷 이후 이벤트 수가 임계치 N을 초과한
 * 세션만</strong> 스냅샷 대상으로 처리한다. 종료됐거나 조용한 세션은 새 이벤트가 없어 자동으로
 * 대상에서 빠지므로, 비활성 세션은 별도 플래그 없이 쿼리로 제외된다.
 *
 * <p>임계치 N은 잠정 500. 설계서 §12.3은 "처음부터 복원 시 1초"를 기준으로 부하 테스트로 확정할
 * 값이라고 명시했으며, 본 구현은 부하 테스트 전까지 잠정값을 사용한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SnapshotScheduler {

	private static final long ONE_HOUR_MILLIS = 60L * 60L * 1000L;
	private static final int SCAN_BATCH_SIZE = 100;
	private static final int SCAN_MAX_SESSIONS = 1000;

	private final SessionRepository sessionRepository;
	private final SnapshotRepository snapshotRepository;
	private final EventRepository eventRepository;
	private final SnapshotApplicationService snapshotApplicationService;

	@Value("${snapshot.threshold:500}")
	private long snapshotThreshold;

	@Scheduled(fixedRateString = "${snapshot.scheduler.interval-ms:" + ONE_HOUR_MILLIS + "}")
	public void run() {
		int processed = 0;
		int taken = 0;
		UUID cursor = null;

		while (processed < SCAN_MAX_SESSIONS) {
			List<Session> batch = sessionRepository.findByFilter(
					SessionStatus.ACTIVE, null, null, null, cursor, SCAN_BATCH_SIZE);
			if (batch.isEmpty()) break;

			for (Session session : batch) {
				if (shouldSnapshot(session.id())) {
					try {
						snapshotApplicationService.snapshotNow(session.id());
						taken++;
					} catch (Exception ex) {
						log.warn("Scheduled snapshot failed for session {}: {}",
								session.id(), ex.getMessage(), ex);
					}
				}
			}
			cursor = batch.get(batch.size() - 1).id();
			processed += batch.size();
		}

		if (taken > 0) {
			log.info("Snapshot scheduler: scanned={}, taken={}", processed, taken);
		}
	}

	private boolean shouldSnapshot(UUID sessionId) {
		UUID lastSnapshotEventId = snapshotRepository.findLatest(sessionId)
				.map(Snapshot::upToEventId).orElse(null);
		long pending = eventRepository.countAfter(sessionId, lastSnapshotEventId);
		return pending > snapshotThreshold;
	}
}
