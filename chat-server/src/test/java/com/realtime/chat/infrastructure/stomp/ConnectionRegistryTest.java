package com.realtime.chat.infrastructure.stomp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryTest {

	private final ConnectionRegistry registry = new ConnectionRegistry();

	@Test
	void first_connection_triggers_first_subscribe_callback() {
		UUID sessionId = UUID.randomUUID();
		List<UUID> firstSubscribed = new ArrayList<>();

		registry.register(sessionId, "conn-1", firstSubscribed::add);

		assertThat(firstSubscribed).containsExactly(sessionId);
		assertThat(registry.contains(sessionId)).isTrue();
	}

	@Test
	void second_connection_does_not_trigger_first_subscribe_callback() {
		UUID sessionId = UUID.randomUUID();
		List<UUID> firstSubscribed = new ArrayList<>();

		registry.register(sessionId, "conn-1", firstSubscribed::add);
		registry.register(sessionId, "conn-2", firstSubscribed::add);

		assertThat(firstSubscribed).containsExactly(sessionId);
		assertThat(registry.connectionsOf(sessionId)).containsExactlyInAnyOrder("conn-1", "conn-2");
	}

	@Test
	void unregister_last_connection_triggers_last_unsubscribe_callback() {
		UUID sessionId = UUID.randomUUID();
		List<UUID> lastUnsubscribed = new ArrayList<>();

		registry.register(sessionId, "conn-1", null);
		registry.register(sessionId, "conn-2", null);

		registry.unregister("conn-1", lastUnsubscribed::add);
		assertThat(lastUnsubscribed).isEmpty(); // 아직 conn-2 남음

		registry.unregister("conn-2", lastUnsubscribed::add);
		assertThat(lastUnsubscribed).containsExactly(sessionId);
		assertThat(registry.contains(sessionId)).isFalse();
	}

	@Test
	void unregister_unknown_connection_returns_false() {
		assertThat(registry.unregister("ghost", id -> {
			throw new AssertionError("should not be called");
		})).isFalse();
	}

	@Test
	void tracked_session_ids_reflects_active_sessions() {
		UUID s1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		registry.register(s1, "conn-a", null);
		registry.register(s2, "conn-b", null);

		assertThat(registry.trackedSessionIds()).containsExactlyInAnyOrder(s1, s2);

		registry.unregister("conn-a", null);
		assertThat(registry.trackedSessionIds()).containsExactly(s2);
	}
}
