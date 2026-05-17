package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.session.SessionState;
import com.realtime.common.domain.session.SessionStatus;
import com.realtime.common.domain.session.Snapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SnapshotDocumentMapper {

	private SnapshotDocumentMapper() {
	}

	public static SnapshotDocument toDocument(Snapshot snapshot) {
		return new SnapshotDocument(
				snapshot.id(),
				snapshot.sessionId(),
				snapshot.upToEventId(),
				stateToMap(snapshot.state()),
				snapshot.snapshotAt()
		);
	}

	public static Snapshot toDomain(SnapshotDocument doc) {
		return new Snapshot(
				doc.getId(),
				doc.getSessionId(),
				doc.getUpToEventId(),
				mapToState(doc.getState()),
				doc.getSnapshotAt()
		);
	}

	private static Map<String, Object> stateToMap(SessionState state) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("participants", new ArrayList<>(state.participants()));
		List<Map<String, Object>> messages = new ArrayList<>();
		state.messages().forEach((eventId, view) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("eventId", eventId.toString());
			m.put("sender", view.sender());
			m.put("content", view.content());
			m.put("status", view.status().name());
			messages.add(m);
		});
		map.put("messages", messages);
		map.put("status", state.status().name());
		return map;
	}

	@SuppressWarnings("unchecked")
	private static SessionState mapToState(Map<String, Object> map) {
		SessionState state = SessionState.empty();
		Collection<String> participants = (Collection<String>) map.getOrDefault("participants", List.of());
		state.participants().addAll(participants);

		List<Map<String, Object>> messages = (List<Map<String, Object>>) map.getOrDefault("messages", List.of());
		for (Map<String, Object> m : messages) {
			UUID eventId = UUID.fromString((String) m.get("eventId"));
			state.messages().put(eventId, new SessionState.MessageView(
					(String) m.get("sender"),
					(String) m.get("content"),
					SessionState.MessageStatus.valueOf((String) m.get("status"))));
		}
		state.status(SessionStatus.valueOf((String) map.getOrDefault("status", "ACTIVE")));
		return state;
	}
}
