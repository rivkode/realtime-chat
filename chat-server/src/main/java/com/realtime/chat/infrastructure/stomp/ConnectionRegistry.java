package com.realtime.chat.infrastructure.stomp;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 채팅 서버 인메모리 연결 테이블(설계서 §8.5).
 *
 * <pre>
 *   주 테이블:   ConcurrentHashMap&lt;sessionId, Set&lt;simpSessionId&gt;&gt;
 *   역방향 보조: ConcurrentHashMap&lt;simpSessionId, sessionId&gt;
 * </pre>
 *
 * <p>접근 패턴 3가지(§8.5):
 * <ol>
 *   <li>Redis 채널에서 세션 이벤트 수신 → 그 세션 연결들을 찾아 push (핫패스, O(1))</li>
 *   <li>Redis 재연결 시 → 이 서버가 재구독할 sessionId 전체</li>
 *   <li>DISCONNECT 시 → 끊긴 connection을 테이블에서 제거</li>
 * </ol>
 *
 * <p>리스트가 아닌 해시맵을 쓰는 이유는 (1)이 가장 잦은 핫패스인데 리스트면 O(n) 전체 순회가 되기
 * 때문. Set으로 둔 이유는 같은 세션의 멀티 디바이스/멀티 연결 자연 처리.
 *
 * <p>{@code subscribeIfFirst} / {@code unsubscribeIfEmpty} 콜백은 Redis 채널의 첫 구독/마지막
 * 해제 시점(§5.3)을 호출자에게 알린다 — Redis Pub/Sub 라이프사이클 연동에 사용.
 */
@Component
public class ConnectionRegistry {

	private final ConcurrentHashMap<UUID, Set<String>> sessionToConnections = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, UUID> connectionToSession = new ConcurrentHashMap<>();

	/**
	 * 연결을 등록한다. 해당 sessionId의 첫 연결이면 {@code onFirstSubscribe} 콜백을 호출.
	 *
	 * @return 이 연결이 sessionId의 첫 연결인지 여부
	 */
	public boolean register(UUID sessionId, String connectionId, Consumer<UUID> onFirstSubscribe) {
		boolean[] wasFirst = {false};
		sessionToConnections.compute(sessionId, (key, existing) -> {
			Set<String> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
			if (set.isEmpty()) {
				wasFirst[0] = true;
			}
			set.add(connectionId);
			return set;
		});
		connectionToSession.put(connectionId, sessionId);
		if (wasFirst[0] && onFirstSubscribe != null) {
			onFirstSubscribe.accept(sessionId);
		}
		return wasFirst[0];
	}

	/**
	 * 연결을 해제한다. 해당 sessionId의 마지막 연결이었으면 {@code onLastUnsubscribe} 콜백을 호출.
	 *
	 * @return 해제된 connection이 마지막이었는지 여부 (없는 connection이면 false)
	 */
	public boolean unregister(String connectionId, Consumer<UUID> onLastUnsubscribe) {
		UUID sessionId = connectionToSession.remove(connectionId);
		if (sessionId == null) return false;

		boolean[] wasLast = {false};
		sessionToConnections.computeIfPresent(sessionId, (key, set) -> {
			set.remove(connectionId);
			if (set.isEmpty()) {
				wasLast[0] = true;
				return null; // 키 자체 제거
			}
			return set;
		});
		if (wasLast[0] && onLastUnsubscribe != null) {
			onLastUnsubscribe.accept(sessionId);
		}
		return wasLast[0];
	}

	/** 해당 sessionId의 모든 connectionId(읽기 전용 view). */
	public Set<String> connectionsOf(UUID sessionId) {
		Set<String> set = sessionToConnections.get(sessionId);
		return set == null ? Set.of() : Collections.unmodifiableSet(set);
	}

	/** 이 서버가 보유한 모든 sessionId(Redis 재연결 시 일괄 재구독용). */
	public Set<UUID> trackedSessionIds() {
		return Collections.unmodifiableSet(sessionToConnections.keySet());
	}

	public boolean contains(UUID sessionId) {
		Set<String> set = sessionToConnections.get(sessionId);
		return set != null && !set.isEmpty();
	}
}
