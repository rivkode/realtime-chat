package com.realtime.common.application;

import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.DuplicateClientEventIdException;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 이벤트 수집 응용 서비스(설계서 §8.1, §9.1).
 *
 * <p>핵심 동작:
 * <ol>
 *   <li>서버에서 UUIDv7 {@code _id} 발급</li>
 *   <li>단일 도큐먼트 INSERT (D2)</li>
 *   <li>{@code {sessionId, clientEventId}} unique 충돌 시 기존 이벤트를 조회해 멱등 ACK로 반환(§9.1)</li>
 * </ol>
 * "무시했다"가 아니라 "이미 처리됐고 결과는 이것"이라고 알려준다.
 */
@Service
@RequiredArgsConstructor
public class EventAppendService {

	private final EventRepository eventRepository;
	private final Clock clock;

	public AppendResult append(UUID sessionId, String actorUserId, UUID clientEventId,
							   EventPayload payload, Instant clientTs) {
		Instant serverTs = clock.instant();
		Event event = new Event(
				UuidV7.generate(),
				sessionId,
				payload.type(),
				actorUserId,
				clientEventId,
				payload,
				clientTs,
				serverTs
		);
		try {
			Event stored = eventRepository.append(event);
			return new AppendResult(stored, false);
		} catch (DuplicateClientEventIdException ex) {
			Event existing = eventRepository.findByClientEventId(sessionId, clientEventId)
					.orElseThrow(() -> new IllegalStateException(
							"Duplicate index fired but existing event not found: " + clientEventId));
			return new AppendResult(existing, true);
		}
	}

	public record AppendResult(Event event, boolean duplicate) {
	}
}
