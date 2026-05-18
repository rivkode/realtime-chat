package com.realtime.api.presentation;

import com.realtime.api.application.TimelineApplicationService;
import com.realtime.api.presentation.dto.TimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 시점 복원 REST(설계서 §16).
 *
 * <p>{@code GET /sessions/{id}/timeline?at=ISO-8601}
 * <ul>
 *   <li>{@code at}이 있으면 그 시점 상태로 리플레이</li>
 *   <li>{@code at}이 없으면 현재 시점 상태</li>
 * </ul>
 *
 * <p>정밀 재현용 {@code at_event_id} 파라미터는 후속 PR(스냅샷·결정론 검증과 함께)로 보류.
 */
@RestController
@RequestMapping("/sessions/{id}/timeline")
@RequiredArgsConstructor
public class TimelineController {

	private final TimelineApplicationService timelineApplicationService;

	@GetMapping
	public TimelineResponse restore(
			@PathVariable("id") UUID sessionId,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at
	) {
		return TimelineResponse.of(timelineApplicationService.restore(sessionId, at));
	}
}
