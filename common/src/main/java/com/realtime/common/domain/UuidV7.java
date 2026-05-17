package com.realtime.common.domain;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/**
 * RFC 9562 UUIDv7 생성기 — Java UUID Generator(JUG)에 위임하는 얇은 wrapper.
 *
 * <p>JUG의 {@link Generators#timeBasedEpochGenerator()}는 monotonic counter를 사용해
 * <strong>같은 밀리초 안에 호출되어도 단조 증가</strong>를 보장한다(설계서 §9.2의 "1ms 내 순서 뒤집힘"
 * 한계를 라이브러리 레벨에서 해소). 정렬·복원·재연결 커서의 기준이 되는 이벤트 {@code _id}로 적합.
 *
 * <p>wrapper로 둔 이유는 (1) 호출처가 단일 진입점을 통해 사용하도록 강제하고,
 * (2) 추후 라이브러리·구현 교체 시 한 곳만 수정하기 위함.
 */
public final class UuidV7 {

	private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

	private UuidV7() {
	}

	public static UUID generate() {
		return GENERATOR.generate();
	}

	/** UUIDv7 앞 48bit에 박힌 unix epoch millis 추출(디버깅·로깅 용). */
	public static long extractTimestampMillis(UUID uuid) {
		return uuid.getMostSignificantBits() >>> 16;
	}
}
