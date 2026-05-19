package com.realtime.chat.presentation.dto;

import java.util.UUID;

/**
 * STOMP {@code SEND /app/sessions/{id}/resume} 페이로드(설계서 §8.4, §9.3).
 *
 * <p>{@code lastEventId}가 null이면 초기 로드(클라이언트가 앱 재설치·캐시 삭제 등으로 기준점을
 * 잃은 경우) — 스냅샷 기반 응답으로 전환된다(§9.3).
 */
public record ResumeRequest(
		UUID lastEventId,
		Integer limit
) {
}
