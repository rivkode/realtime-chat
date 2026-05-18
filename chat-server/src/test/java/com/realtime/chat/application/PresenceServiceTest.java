package com.realtime.chat.application;

import com.realtime.chat.application.broadcast.PresenceBroadcast;
import com.realtime.chat.application.broadcast.PresenceStatus;
import com.realtime.chat.application.broadcast.SessionChannelMessage;
import com.realtime.chat.infrastructure.redis.PresenceStore;
import com.realtime.chat.infrastructure.redis.SessionChannelPublisher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceServiceTest {

	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T23:00:00Z"), ZoneOffset.UTC);
	private final RecordingPublisher publisher = new RecordingPublisher();
	private final FakePresenceStore presenceStore = new FakePresenceStore();
	private final PresenceService service = new PresenceService(presenceStore, publisher, fixedClock);

	@Test
	void on_join_sets_online_and_publishes_presence() {
		UUID sessionId = UUID.randomUUID();

		service.onJoin(sessionId, "user-1");

		assertThat(presenceStore.isOnline(sessionId, "user-1")).isTrue();
		assertThat(publisher.published).hasSize(1);
		assertThat(publisher.published.get(0)).isInstanceOf(PresenceBroadcast.class);
		PresenceBroadcast pb = (PresenceBroadcast) publisher.published.get(0);
		assertThat(pb.status()).isEqualTo(PresenceStatus.ONLINE);
		assertThat(pb.userId()).isEqualTo("user-1");
	}

	@Test
	void on_leave_clears_online_and_publishes_offline() {
		UUID sessionId = UUID.randomUUID();
		service.onJoin(sessionId, "user-1");
		publisher.published.clear();

		service.onLeave(sessionId, "user-1");

		assertThat(presenceStore.isOnline(sessionId, "user-1")).isFalse();
		assertThat(publisher.published).hasSize(1);
		PresenceBroadcast pb = (PresenceBroadcast) publisher.published.get(0);
		assertThat(pb.status()).isEqualTo(PresenceStatus.OFFLINE);
	}

	@Test
	void on_heartbeat_renews_ttl_without_publishing() {
		// §8.3: heartbeat은 TTL 갱신만, 상태 transition 아니므로 publish 없음
		UUID sessionId = UUID.randomUUID();

		service.onHeartbeat(sessionId, "user-1");

		assertThat(presenceStore.isOnline(sessionId, "user-1")).isTrue();
		assertThat(publisher.published).isEmpty();
	}

	private static class RecordingPublisher extends SessionChannelPublisher {
		private final List<SessionChannelMessage> published = new ArrayList<>();

		RecordingPublisher() {
			super(null);
		}

		@Override
		public void publish(SessionChannelMessage message) {
			published.add(message);
		}
	}

	private static class FakePresenceStore extends PresenceStore {
		private final java.util.Set<String> online = ConcurrentHashMap.newKeySet();

		FakePresenceStore() {
			super(null);
		}

		@Override
		public void markOnline(UUID sessionId, String userId) {
			online.add(sessionId + ":" + userId);
		}

		@Override
		public void markOffline(UUID sessionId, String userId) {
			online.remove(sessionId + ":" + userId);
		}

		@Override
		public boolean isOnline(UUID sessionId, String userId) {
			return online.contains(sessionId + ":" + userId);
		}
	}
}
