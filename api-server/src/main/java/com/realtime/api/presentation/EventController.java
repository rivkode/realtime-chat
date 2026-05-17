package com.realtime.api.presentation;

import com.realtime.api.presentation.dto.AppendEventRequest;
import com.realtime.api.presentation.dto.AppendEventResponse;
import com.realtime.api.presentation.dto.EventListResponse;
import com.realtime.api.presentation.dto.EventView;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.EventAppendService.AppendResult;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 이벤트 수집·조회 REST(설계서 §16).
 * <ul>
 *   <li>{@code POST /sessions/{id}/events} — WebSocket 수집과 동일한 멱등 경로를 HTTP로도 노출(§9.1)</li>
 *   <li>{@code GET  /sessions/{id}/events} — 검증·디버깅용 증분 조회(§9.3 Pull sync 동작 확인)</li>
 * </ul>
 */
@RestController
@RequestMapping("/sessions/{id}/events")
@RequiredArgsConstructor
public class EventController {

	private static final int MAX_LIST_LIMIT = 500;
	private static final int DEFAULT_LIST_LIMIT = 100;

	private final EventAppendService eventAppendService;
	private final EventRepository eventRepository;

	@PostMapping
	public ResponseEntity<AppendEventResponse> append(@PathVariable("id") UUID sessionId,
													  @Valid @RequestBody AppendEventRequest request) {
		EventPayload payload = EventPayload.fromMap(request.type(), request.payload());
		AppendResult result = eventAppendService.append(
				sessionId, request.actorUserId(), request.clientEventId(),
				payload, request.clientTs());
		Event stored = result.event();
		HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(new AppendEventResponse(stored.id(), stored.serverTs()));
	}

	@GetMapping
	public EventListResponse list(@PathVariable("id") UUID sessionId,
								  @RequestParam(name = "after", required = false) UUID afterEventId,
								  @RequestParam(required = false) Integer limit) {
		int effectiveLimit = clampLimit(limit);
		List<Event> events = eventRepository.findAfter(sessionId, afterEventId, effectiveLimit);
		List<EventView> views = events.stream().map(EventView::of).toList();
		UUID nextCursor = views.size() == effectiveLimit ? views.get(views.size() - 1).eventId() : null;
		return new EventListResponse(views, nextCursor);
	}

	private int clampLimit(Integer limit) {
		if (limit == null || limit <= 0) return DEFAULT_LIST_LIMIT;
		return Math.min(limit, MAX_LIST_LIMIT);
	}
}
