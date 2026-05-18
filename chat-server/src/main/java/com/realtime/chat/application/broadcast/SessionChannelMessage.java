package com.realtime.chat.application.broadcast;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * 세션 채널 {@code channel:session:{id}}에 흐르는 메시지의 공통 wire 인터페이스(설계서 §8.3).
 *
 * <p>설계서 §8.3은 "세션 채널 하나로 그 세션의 모든 라이브 이벤트(메시지·join·leave·presence)를
 * 흘려보낸다"고 못 박는다 — 별도 presence 채널을 두면 구독자가 채널을 둘 구독해야 하므로 통합.
 *
 * <p>두 종류를 sealed로 닫고 Jackson polymorphic 어노테이션({@code kind} discriminator)을 붙여
 * subscriber 측이 type-safe하게 분기 처리한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
		@JsonSubTypes.Type(value = SessionEventBroadcast.class, name = "event"),
		@JsonSubTypes.Type(value = PresenceBroadcast.class, name = "presence")
})
public sealed interface SessionChannelMessage
		permits SessionEventBroadcast, PresenceBroadcast {

	UUID sessionId();
}
