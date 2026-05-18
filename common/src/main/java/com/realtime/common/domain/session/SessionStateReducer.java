package com.realtime.common.domain.session;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;

/**
 * 이벤트 소싱 리듀서 — {@link SessionState}를 한 이벤트씩 fold하는 <strong>순수 함수</strong>.
 *
 * <h2>fold/reduce 패턴</h2>
 * <pre>
 *   초기 상태 ─┐
 *              ├─ reduce(s, e₁) → s₁ ─ reduce(s₁, e₂) → s₂ ─ ... → 최종 상태
 *   이벤트 시퀀스 ┘
 * </pre>
 * 이벤트 소싱은 상태가 아니라 사건의 시퀀스를 저장하므로, 어느 시점의 상태가 필요할 때마다
 * 그 시점까지의 이벤트를 순서대로 누적 적용해 만들어낸다. {@link #foldAll}이 누적을 돌리고
 * {@link #reduce}가 한 스텝의 변환을 정의한다.
 *
 * <h2>설계서 §10.2 결정론 원칙</h2>
 * 외부 호출·랜덤·현재시각 참조 금지. 같은 (state, event) 쌍에 대해 항상 같은 결과가 나와야 한다.
 * 이게 무너지면 같은 timeline 조회가 시점마다 다른 결과를 내는 사태가 됨 — 시점 복원의 정확성을
 * 담보하는 가장 근본 가정이다. CLAUDE.md가 "복원 결정론 검증 테스트 반드시"라고 못 박은 이유.
 * 참고로 {@code event.serverTs}는 이벤트에 박힌 값이라 "외부 시각"이 아니다 — 입력의 일부.
 *
 * <h2>§10.3 방어 (out-of-order 내성)</h2>
 * {@code message_edited}/{@code message_deleted}의 {@code targetEventId}가 현재 상태에 없으면
 * no-op. 수정/삭제 이벤트가 원본 메시지보다 먼저 와도 리듀서가 깨지지 않는다 — Pull 복구나
 * 멀티 디바이스 재동기화 시 도착 순서가 어긋날 수 있다.
 *
 * <h2>switch pattern matching</h2>
 * {@link EventPayload}는 sealed interface라 컴파일러가 모든 하위 타입을 알고 있다.
 * Java 21 switch + record pattern으로 분기하면 <strong>exhaustiveness 검사</strong>가 켜져,
 * 새 {@code EventPayload} 하위 타입이 생기면 컴파일러가 누락된 case를 강제로 잡아낸다.
 * "새 이벤트 추가 시 리듀서에 케이스 빠져 조용히 무시되는" 버그를 원천 차단.
 *
 * <h2>mutation 정책</h2>
 * 구현은 {@code state.participants().add(...)} 식으로 입력 state를 in-place 수정한다.
 * 함수형 정석은 매번 새 객체를 만드는 것이지만 — (1) 한 복원에서 수백~수천 번 갱신되어 매번
 * deep copy하면 비용이 크고, (2) 호출자(api-server의 {@code TimelineApplicationService})가
 * 매번 {@link SessionState#empty()} 또는 스냅샷 state를 base로 새로 잡아 부르므로 입력 state
 * 오염 위험이 없다. 결정론은 "같은 base + 같은 이벤트 시퀀스 → 같은 결과" 형태로 유지된다.
 */
public final class SessionStateReducer {

	private SessionStateReducer() {
	}

	/**
	 * 현재 상태 {@code state}에 이벤트 {@code event}를 적용한 뒤 상태를 반환한다.
	 * 분기는 {@code event.payload()}의 실제 타입에 의해 결정되며, 모든 분기는 순수하다.
	 *
	 * @return 갱신된 state (현재 구현상 입력 state와 동일 참조 — mutation 정책 참고)
	 */
	public static SessionState reduce(SessionState state, Event event) {
		switch (event.payload()) {
			case EventPayload.SessionCreated ignored -> {
				// 빈 세션 초기화 — 이미 SessionState.empty()로 시작하므로 추가 작업 없음
			}
			case EventPayload.ParticipantJoined p -> state.participants().add(p.userId());
			case EventPayload.ParticipantLeft p -> state.participants().remove(p.userId());
			case EventPayload.MessageSent p -> state.messages().put(event.id(),
					new SessionState.MessageView(event.actorUserId(), p.content(),
							SessionState.MessageStatus.SENT));
			case EventPayload.MessageEdited p -> {
				SessionState.MessageView existing = state.messages().get(p.targetEventId());
				if (existing != null) {
					state.messages().put(p.targetEventId(),
							existing.withContent(p.content(), SessionState.MessageStatus.EDITED));
				}
			}
			case EventPayload.MessageDeleted p -> {
				SessionState.MessageView existing = state.messages().get(p.targetEventId());
				if (existing != null) {
					state.messages().put(p.targetEventId(),
							existing.withContent(existing.content(), SessionState.MessageStatus.DELETED));
				}
			}
			case EventPayload.SessionEnded ignored -> state.status(SessionStatus.ENDED);
		}
		return state;
	}

	public static SessionState foldAll(SessionState initial, Iterable<Event> events) {
		SessionState state = initial;
		for (Event event : events) {
			state = reduce(state, event);
		}
		return state;
	}
}
