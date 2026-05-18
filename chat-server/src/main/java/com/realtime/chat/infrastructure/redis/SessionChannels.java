package com.realtime.chat.infrastructure.redis;

import java.util.UUID;

/** Redis 채널 이름 규약(설계서 §5.3). */
public final class SessionChannels {

	private static final String PREFIX = "channel:session:";

	private SessionChannels() {
	}

	public static String of(UUID sessionId) {
		return PREFIX + sessionId;
	}
}
