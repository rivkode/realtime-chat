package com.realtime.api.presentation;

import com.realtime.api.application.SessionApplicationService;
import com.realtime.api.application.SessionApplicationService.EndOutcome;
import com.realtime.api.application.SessionApplicationService.JoinOutcome;
import com.realtime.api.presentation.dto.CreateSessionRequest;
import com.realtime.api.presentation.dto.EndSessionRequest;
import com.realtime.api.presentation.dto.EndSessionResponse;
import com.realtime.api.presentation.dto.JoinSessionRequest;
import com.realtime.api.presentation.dto.JoinSessionResponse;
import com.realtime.api.presentation.dto.SessionListResponse;
import com.realtime.api.presentation.dto.SessionResponse;
import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

	private static final int MAX_LIST_LIMIT = 100;
	private static final int DEFAULT_LIST_LIMIT = 20;

	private final SessionApplicationService sessionApplicationService;

	@PostMapping
	public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
		Session session = sessionApplicationService.create(request.createdBy());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(SessionResponse.of(session, sessionApplicationService.findParticipants(session.id())));
	}

	@PostMapping("/{id}/join")
	public ResponseEntity<JoinSessionResponse> join(@PathVariable("id") UUID sessionId,
													@Valid @RequestBody JoinSessionRequest request) {
		JoinOutcome outcome = sessionApplicationService.join(sessionId, request.userId(), request.clientEventId());
		HttpStatus status = outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(new JoinSessionResponse(
				outcome.event().id(), outcome.session().id(), outcome.session().status()));
	}

	@PostMapping("/{id}/end")
	public ResponseEntity<EndSessionResponse> end(@PathVariable("id") UUID sessionId,
												  @Valid @RequestBody EndSessionRequest request) {
		EndOutcome outcome = sessionApplicationService.end(sessionId, request.endedBy(), request.clientEventId());
		return ResponseEntity.ok(EndSessionResponse.of(outcome.session()));
	}

	@GetMapping("/{id}")
	public SessionResponse get(@PathVariable("id") UUID sessionId) {
		Session session = sessionApplicationService.findById(sessionId);
		return SessionResponse.of(session, sessionApplicationService.findParticipants(sessionId));
	}

	@GetMapping
	public SessionListResponse list(
			@RequestParam(required = false) SessionStatus status,
			@RequestParam(required = false) String participant,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) UUID cursor,
			@RequestParam(required = false) Integer limit
	) {
		int effectiveLimit = clampLimit(limit);
		List<Session> sessions = sessionApplicationService.find(status, participant, from, to, cursor, effectiveLimit);
		List<SessionResponse> items = sessions.stream()
				.map(s -> SessionResponse.of(s, sessionApplicationService.findParticipants(s.id())))
				.toList();
		UUID nextCursor = items.size() == effectiveLimit ? items.get(items.size() - 1).id() : null;
		return new SessionListResponse(items, nextCursor);
	}

	private int clampLimit(Integer limit) {
		if (limit == null || limit <= 0) return DEFAULT_LIST_LIMIT;
		return Math.min(limit, MAX_LIST_LIMIT);
	}
}
