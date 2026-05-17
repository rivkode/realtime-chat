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
	void embedded_timestamp_round_trip() {
		long now = System.currentTimeMillis();
		UUID uuid = UuidV7.generate(now);
		assertThat(UuidV7.extractTimestampMillis(uuid)).isEqualTo(now);
	}

	@Test
	void successive_ids_sort_by_time() {
		UUID earlier = UuidV7.generate(1_000L);
		UUID later = UuidV7.generate(2_000L);
		assertThat(earlier.compareTo(later)).isNegative();
	}
}
