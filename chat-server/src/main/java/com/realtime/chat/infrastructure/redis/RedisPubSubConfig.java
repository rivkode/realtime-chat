package com.realtime.chat.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Pub/Sub 인프라(설계서 §5.3).
 *
 * <p>{@link RedisTemplate} — key는 String, value는 JSON. {@link SessionChannelPublisher}가
 * {@link com.realtime.chat.application.broadcast.SessionEventBroadcast}를 직렬화해 발행한다.
 *
 * <p>{@link RedisMessageListenerContainer} — 채널 동적 구독/해제를 지원하는 컨테이너.
 * {@link SessionChannelSubscriber}가 세션 첫 연결 시 채널을 추가하고 마지막 끊김 시 제거한다.
 */
@Configuration
public class RedisPubSubConfig {

	@Bean
	public ObjectMapper redisObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		return mapper;
	}

	@Bean
	public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
													   ObjectMapper redisObjectMapper) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));
		template.setHashKeySerializer(new StringRedisSerializer());
		template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));
		template.afterPropertiesSet();
		return template;
	}

	@Bean
	public RedisMessageListenerContainer redisMessageListenerContainer(
			RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		return container;
	}
}
