package com.realtime.common.infrastructure.mongo;

import com.mongodb.WriteConcern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.WriteConcernResolver;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.List;

/**
 * MongoDB 공통 설정.
 * - write concern {@code MAJORITY}: 단일 도큐먼트 INSERT가 복제 다수에서 확인된 뒤 ACK(설계서 §15.3).
 * - 인덱스 자동 생성은 {@code application.yaml}에서 활성화한다
 *   ({@code spring.data.mongodb.auto-index-creation=true}).
 */
@Configuration
public class MongoConfig {

	@Bean
	public WriteConcernResolver writeConcernResolver() {
		return action -> WriteConcern.MAJORITY;
	}

	@Bean
	public MongoCustomConversions mongoCustomConversions() {
		return new MongoCustomConversions(List.of());
	}
}
