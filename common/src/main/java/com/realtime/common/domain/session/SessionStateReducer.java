package com.realtime.common.domain.session;

import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;

/**
 * 이벤트 소싱 리듀서 — {@link SessionState}를 한 이벤트씩 fold하는 <strong>순수 함수</strong>.
 *
 * <p>설계서 §10.2 결정론 원칙: 외부 호출·랜덤·현재시각 참조 금지. 같은 이벤트 시퀀스를 같은 순서로
 * 적용하면 항상 같은 결과가 나와야 한다.
 *
 * <p>§10.3 방어: {@code message_edited}/{@code message_deleted}의 {@code targetEventId}가
 * 현재 상태에 없으면 no-op — 수정/삭제가 원본보다 먼저 와도 깨지지 않는다.
 */
public final class SessionStateReducer {

	private SessionStateReducer() {
	}

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
