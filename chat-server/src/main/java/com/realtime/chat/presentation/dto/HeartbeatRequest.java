package com.realtime.chat.presentation.dto;

/**
 * STOMP {@code SEND /app/sessions/{id}/heartbeat} 페이로드.
 *
 * <p>설계서 §8.3은 "STOMP 프로토콜 레벨 heartbeat 활용"을 언급하지만, Spring STOMP 구현상
 * 애플리케이션 레벨에서 raw STOMP heartbeat frame을 받기 어려워(destination·sessionId를 모름)
 * 명시적 SEND로 구현한다. 부하는 사용자당 10초 1건 수준이라 무시할 수 있다.
 */
public record HeartbeatRequest(String userId) {
}
