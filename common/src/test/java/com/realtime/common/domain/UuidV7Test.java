package com.realtime.common.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

	@Test
	void generates_version_7_and_rfc_variant() {
		UUID uuid = UuidV7.generate();
		assertThat(uuid.version()).isEqualTo(7);
		assertThat(uuid.variant()).isEqualTo(2);
	}

	@Test
	void embedded_timestamp_is_close_to_wall_clock() {
		long before = System.currentTimeMillis();
		UUID uuid = UuidV7.generate();
		long after = System.currentTimeMillis();

		long extracted = UuidV7.extractTimestampMillis(uuid);
		assertThat(extracted).isBetween(before, after);
	}

	@Test
	void successive_ids_are_monotonically_increasing() {
		// JUG의 timeBasedEpochGenerator는 같은 ms 안에서도 monotonic counter로
		// 단조 증가를 보장한다(설계서 §9.2의 "1ms 내 순서 뒤집힘" 한계 해소).
		UUID first = UuidV7.generate();
		UUID second = UuidV7.generate();
		UUID third = UuidV7.generate();

		assertThat(first.compareTo(second)).isNegative();
		assertThat(second.compareTo(third)).isNegative();
	}
}
