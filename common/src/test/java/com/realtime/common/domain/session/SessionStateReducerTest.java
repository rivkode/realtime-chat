package com.realtime.common.domain.session;

import com.realtime.common.domain.UuidV7;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventPayload;
import com.realtime.common.domain.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계서 §10.2 결정론 검증 — 같은 이벤트 시퀀스를 같은 순서로 적용하면 항상 같은 결과.
 * CLAUDE.md 요구: "복원 결정론 검증 테스트(같은 이벤트 → 같은 상태)를 반드시 포함".
 */
class SessionStateReducerTest {

	private static final UUID SESSION_ID = UUID.randomUUID();

	@Test
	void identical_event_sequences_produce_identical_state() {
		List<Event> events = List.of(
				join("user-1"),
				join("user-2"),
				message("user-1", "hi"),
				message("user-2", "hello")
		);

		SessionState first = SessionStateReducer.foldAll(SessionState.empty(), events);
		SessionState second = SessionStateReducer.foldAll(SessionState.empty(), events);

		assertThat(first.participants()).containsExactly("user-1", "user-2");
		assertThat(first.participants()).isEqualTo(second.participants());
		assertThat(first.messages().keySet()).isEqualTo(second.messages().keySet());
		assertThat(first.status()).isEqualTo(second.status());
	}

	@Test
	void participant_left_removes_user() {
		Event joined = join("user-1");
		Event left = participantLeft("user-1");

		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(joined, left));

		assertThat(state.participants()).isEmpty();
	}

	@Test
	void session_ended_marks_status() {
		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(
				sessionEnded("user-1")
		));
		assertThat(state.status()).isEqualTo(SessionStatus.ENDED);
	}

	@Test
	void message_edited_updates_content_and_status() {
		Event sent = message("user-1", "original");
		Event edited = messageEdited("user-1", sent.id(), "fixed");

		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(sent, edited));

		SessionState.MessageView view = state.messages().get(sent.id());
		assertThat(view.content()).isEqualTo("fixed");
		assertThat(view.status()).isEqualTo(SessionState.MessageStatus.EDITED);
	}

	@Test
	void message_deleted_soft_deletes() {
		Event sent = message("user-1", "secret");
		Event deleted = messageDeleted("user-1", sent.id());

		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(sent, deleted));

		SessionState.MessageView view = state.messages().get(sent.id());
		assertThat(view.status()).isEqualTo(SessionState.MessageStatus.DELETED);
		assertThat(view.content()).isEqualTo("secret"); // soft-delete — 본문 보존
	}

	@Test
	void edit_without_target_is_noop() {
		// 방어 §10.3: 수정이 원본보다 먼저 와도 깨지지 않는다.
		UUID phantomTarget = UUID.randomUUID();
		Event orphanEdit = messageEdited("user-1", phantomTarget, "ghost");

		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(orphanEdit));

		assertThat(state.messages()).isEmpty();
	}

	@Test
	void delete_without_target_is_noop() {
		UUID phantomTarget = UUID.randomUUID();
		Event orphanDelete = messageDeleted("user-1", phantomTarget);

		SessionState state = SessionStateReducer.foldAll(SessionState.empty(), List.of(orphanDelete));

		assertThat(state.messages()).isEmpty();
	}

	@Test
	void reduce_is_pure_does_not_depend_on_external_state() {
		// 같은 입력을 두 번 reduce해 같은 출력 — 순수 함수성 검증
		List<Event> events = sampleConversation();

		SessionState a = SessionStateReducer.foldAll(SessionState.empty(), events);
		SessionState b = SessionStateReducer.foldAll(SessionState.empty(), events);

		assertThat(a.participants()).isEqualTo(b.participants());
		assertThat(a.messages().keySet()).isEqualTo(b.messages().keySet());
		assertThat(a.status()).isEqualTo(b.status());
	}

	@Test
	void shuffled_events_when_sorted_by_id_produce_identical_state() {
		// 호출자 책임으로 _id 정렬을 한다는 전제(설계서 §9.2, §10.3).
		// 도착 순서가 뒤바뀌어 와도 sorted 후 reduce하면 동일 결과.
		List<Event> ordered = sampleConversation();
		List<Event> shuffled = new ArrayList<>(ordered);
		Collections.shuffle(shuffled);
		shuffled.sort((e1, e2) -> e1.id().compareTo(e2.id()));

		SessionState fromOrdered = SessionStateReducer.foldAll(SessionState.empty(), ordered);
		SessionState fromShuffled = SessionStateReducer.foldAll(SessionState.empty(), shuffled);

		assertThat(fromShuffled.participants()).containsExactlyElementsOf(fromOrdered.participants());
		assertThat(fromShuffled.messages().keySet()).isEqualTo(fromOrdered.messages().keySet());
		assertThat(fromShuffled.status()).isEqualTo(fromOrdered.status());
	}

	private List<Event> sampleConversation() {
		Event j1 = join("user-1");
		Event j2 = join("user-2");
		Event m1 = message("user-1", "hi");
		Event m2 = message("user-2", "hello");
		Event m3 = message("user-1", "how are you");
		Event edit = messageEdited("user-1", m1.id(), "hi!");
		return List.of(j1, j2, m1, m2, m3, edit);
	}

	private Event join(String userId) {
		EventPayload payload = new EventPayload.ParticipantJoined(userId);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.PARTICIPANT_JOINED,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}

	private Event participantLeft(String userId) {
		EventPayload payload = new EventPayload.ParticipantLeft(userId);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.PARTICIPANT_LEFT,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}

	private Event message(String userId, String content) {
		EventPayload payload = new EventPayload.MessageSent(content);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.MESSAGE_SENT,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}

	private Event messageEdited(String userId, UUID targetId, String content) {
		EventPayload payload = new EventPayload.MessageEdited(targetId, content);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.MESSAGE_EDITED,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}

	private Event messageDeleted(String userId, UUID targetId) {
		EventPayload payload = new EventPayload.MessageDeleted(targetId);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.MESSAGE_DELETED,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}

	private Event sessionEnded(String userId) {
		EventPayload payload = new EventPayload.SessionEnded(userId);
		return new Event(UuidV7.generate(), SESSION_ID, EventType.SESSION_ENDED,
				userId, UUID.randomUUID(), payload, null, Instant.now(), null);
	}
}
