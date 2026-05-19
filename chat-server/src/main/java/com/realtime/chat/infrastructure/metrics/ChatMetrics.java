package com.realtime.chat.infrastructure.metrics;

import com.realtime.chat.infrastructure.stomp.ConnectionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * chat-server의 핵심 메트릭(설계서 §14.3).
 *
 * <p>단계별 카운터 — 한 메시지의 처리 단계마다 분기 가능:
 * <pre>
 *   chat_event_received  → STOMP /messages 진입  (발신측)
 *   chat_event_persisted → events INSERT 성공     (발신측)
 *   chat_event_published → Redis publish 성공     (발신측)
 *   chat_event_delivered → 상대 WS push 성공       (수신측, 다른 서버에서 증가)
 * </pre>
 *
 * <p>처리 지연 — WS 수신 ~ ACK 응답 (§14.3의 p50/p95/p99 대상).
 *
 * <p>Gauge — 활성 WebSocket 연결 수 = {@code ConnectionRegistry.trackedSessionIds().size()} (sessions
 * 기준) 또는 모든 connectionId 합. 본 구현은 sessions 기준 — "이 서버가 보유한 활성 세션 수".
 */
@Component
@RequiredArgsConstructor
public class ChatMetrics {

	private final MeterRegistry meterRegistry;
	private final ConnectionRegistry connectionRegistry;

	private Counter received;
	private Counter persisted;
	private Counter publishedOk;
	private Counter publishedFail;
	private Counter delivered;
	private Counter deliveredFail;
	private Timer dispatchDuration;

	@PostConstruct
	void init() {
		received      = Counter.builder("chat.event.received").description("STOMP message received").register(meterRegistry);
		persisted     = Counter.builder("chat.event.persisted").description("Event INSERT success").register(meterRegistry);
		publishedOk   = Counter.builder("chat.event.published").tag("result", "success").description("Redis publish").register(meterRegistry);
		publishedFail = Counter.builder("chat.event.published").tag("result", "failure").description("Redis publish").register(meterRegistry);
		delivered     = Counter.builder("chat.event.delivered").tag("result", "success").description("STOMP push to peer").register(meterRegistry);
		deliveredFail = Counter.builder("chat.event.delivered").tag("result", "failure").description("STOMP push to peer").register(meterRegistry);
		dispatchDuration = Timer.builder("chat.message.dispatch")
				.description("WS receive ~ ACK")
				.publishPercentiles(0.5, 0.95, 0.99)
				.register(meterRegistry);

		meterRegistry.gauge("chat.websocket.active.sessions",
				connectionRegistry, r -> r.trackedSessionIds().size());
	}

	public void recordReceived()      { received.increment(); }
	public void recordPersisted()     { persisted.increment(); }
	public void recordPublished(boolean success) {
		(success ? publishedOk : publishedFail).increment();
	}
	public void recordDelivered(boolean success) {
		(success ? delivered : deliveredFail).increment();
	}
	public Timer.Sample startDispatchSample() {
		return Timer.start(meterRegistry);
	}
	public void stopDispatch(Timer.Sample sample) {
		sample.stop(dispatchDuration);
	}
}
