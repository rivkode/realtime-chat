package com.realtime.common.domain.event;

/**
 * 영속 이벤트 카탈로그 (설계서 §6.2).
 * connect/disconnect는 휘발성이라 영속 이벤트로 두지 않는다 — presence는 Redis TTL로 다룬다.
 */
public enum EventType {
	SESSION_CREATED,
	PARTICIPANT_JOINED,
	PARTICIPANT_LEFT,
	MESSAGE_SENT,
	MESSAGE_EDITED,
	MESSAGE_DELETED,
	SESSION_ENDED
}
