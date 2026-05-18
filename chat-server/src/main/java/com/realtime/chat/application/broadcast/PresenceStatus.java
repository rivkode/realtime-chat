package com.realtime.chat.application.broadcast;

/**
 * 세션 내 상대의 접속 상태(설계서 §8.3).
 *
 * <ul>
 *   <li>{@link #ONLINE}  — 멤버이고 지금 이 방을 보고 있음 (heartbeat 활성)</li>
 *   <li>{@link #OFFLINE} — 명시적 leave/end 또는 heartbeat TTL 만료(비정상 단절)</li>
 * </ul>
 *
 * <p>설계서 §8.3의 세 상태 중 "접속 중"과 "끊김"에 해당. "세션 떠남"은 {@code participant_left}
 * 이벤트로 별도 처리되므로 presence와 직교적이다.
 */
public enum PresenceStatus {
	ONLINE,
	OFFLINE
}
