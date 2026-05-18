package com.realtime.chat.application;

import com.realtime.chat.application.broadcast.SessionEventBroadcast;
import com.realtime.chat.infrastructure.redis.SessionChannelPublisher;
import com.realtime.common.application.EventAppendService;
import com.realtime.common.application.EventAppendService.AppendResult;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 메시지 수집·전달 핫패스(설계서 §8.1).
 *
 * <ol>
 *   <li>{@link EventAppendService}로 단일 도큐먼트 INSERT — 멱등 ACK가 여기서 결정된다(§9.1)</li>
 *   <li>저장 성공 후 세션 채널에 PUBLISH — best-effort(§9.3), 실패해도 데이터 손실 없음</li>
 * </ol>
 *
 * <p>중복(이미 같은 client_event_id가 저장된 경우)이면 {@code AppendResult.duplicate()=true} —
 * 이 경우 Pub/Sub publish는 생략한다. 이미 한 번 발행됐고, 못 받은 수신자는 Pull 복구가 메우므로
 * 중복 발행할 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class MessageDispatchService {

	private final EventAppendService eventAppendService;
	private final SessionChannelPublisher sessionChannelPublisher;

	public DispatchResult dispatch(UUID sessionId, String actorUserId, UUID clientEventId, String content) {
		AppendResult result = eventAppendService.append(
				sessionId, actorUserId, clientEventId,
				new EventPayload.MessageSent(content), null);

		if (!result.duplicate()) {
			sessionChannelPublisher.publish(SessionEventBroadcast.of(result.event()));
		}
		return new DispatchResult(result.event(), result.duplicate());
	}

	public record DispatchResult(Event event, boolean duplicate) {
	}
}
