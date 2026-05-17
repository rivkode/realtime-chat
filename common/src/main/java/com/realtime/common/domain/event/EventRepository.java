package com.realtime.common.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 진실의 원천 {@code events} 컬렉션에 대한 도메인 Repository 계약.
 * 구현은 infrastructure 레이어가 담당한다(DDD 의존성 역전, CLAUDE.md).
 */
public interface EventRepository {

	/**
	 * 단일 도큐먼트 INSERT(설계서 D2). {@code {sessionId, clientEventId}} unique index가
	 * 중복을 차단한다 — 중복이면 {@link DuplicateClientEventIdException}를 던진다(§9.1).
	 */
	Event append(Event event);

	/** {@code clientEventId}로 조회. 중복 INSERT 충돌 시 기존 이벤트를 ACK하기 위해(§9.1). */
	Optional<Event> findByClientEventId(UUID sessionId, UUID clientEventId);

	/** 재연결 증분 동기화 — {@code _id > lastEventId}인 이벤트를 시간순으로(§9.3). */
	List<Event> findAfter(UUID sessionId, UUID lastEventId, int limit);

	/** 시점 복원 리플레이 — 스냅샷 이후 ~ {@code at} 이전 이벤트(§10). */
	List<Event> findForReplay(UUID sessionId, UUID afterEventIdExclusive, Instant atInclusive);

	/** 최근 N개 메시지 조회 — {@code type=MESSAGE_SENT} 필터(§11 Q1). */
	List<Event> findRecentMessages(UUID sessionId, int limit);

	/** 마지막 스냅샷 이후 이벤트 개수 — 스냅샷 트리거 임계치 판정용(§12.3). */
	long countAfter(UUID sessionId, UUID lastSnapshotEventId);
}
