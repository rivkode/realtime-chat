package com.realtime.common.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 — 앞 48bit 밀리초 타임스탬프 + 나머지 난수.
 * 시간순 정렬이 가능하므로 {@code _id} 정렬이 그대로 수신 순서가 된다(설계서 §2.3, §9.2).
 */
public final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	public static UUID generate() {
		return generate(System.currentTimeMillis());
	}

	public static UUID generate(long unixTimestampMillis) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);

		bytes[0] = (byte) ((unixTimestampMillis >>> 40) & 0xFF);
		bytes[1] = (byte) ((unixTimestampMillis >>> 32) & 0xFF);
		bytes[2] = (byte) ((unixTimestampMillis >>> 24) & 0xFF);
		bytes[3] = (byte) ((unixTimestampMillis >>> 16) & 0xFF);
		bytes[4] = (byte) ((unixTimestampMillis >>> 8) & 0xFF);
		bytes[5] = (byte) (unixTimestampMillis & 0xFF);

		bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
		bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

		long msb = 0;
		long lsb = 0;
		for (int i = 0; i < 8; i++) {
			msb = (msb << 8) | (bytes[i] & 0xFFL);
		}
		for (int i = 8; i < 16; i++) {
			lsb = (lsb << 8) | (bytes[i] & 0xFFL);
		}
		return new UUID(msb, lsb);
	}

	public static long extractTimestampMillis(UUID uuid) {
		return uuid.getMostSignificantBits() >>> 16;
	}
}
