package com.realtime.api.infrastructure.mongo;

import com.realtime.common.domain.event.DuplicateClientEventIdException;
import com.realtime.common.domain.event.Event;
import com.realtime.common.domain.event.EventRepository;
import com.realtime.common.domain.event.EventType;
import com.realtime.common.infrastructure.mongo.EventDocument;
import com.realtime.common.infrastructure.mongo.EventDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * api-server 측 {@link EventRepository} 구현(MongoDB).
 * 도메인 인터페이스를 인프라가 구현(의존성 역전, CLAUDE.md DDD 원칙).
 * Document/매퍼는 common이 공유하지만, 쿼리 구현은 사용처 책임이라 각 서버 모듈에서 정의한다.
 * Spring Data가 MongoDB 드라이버 예외를 {@link DuplicateKeyException}으로 매핑한다.
 */
@Repository
@RequiredArgsConstructor
public class MongoEventRepository implements EventRepository {

	private final MongoTemplate mongoTemplate;

	@Override
	public Event append(Event event) {
		EventDocument doc = EventDocumentMapper.toDocument(event);
		try {
			mongoTemplate.insert(doc);
		} catch (DuplicateKeyException ex) {
			throw new DuplicateClientEventIdException(event.sessionId(), event.clientEventId());
		}
		return event;
	}

	@Override
	public Optional<Event> findByClientEventId(UUID sessionId, UUID clientEventId) {
		Query query = new Query(Criteria.where("sessionId").is(sessionId)
				.and("clientEventId").is(clientEventId));
		EventDocument doc = mongoTemplate.findOne(query, EventDocument.class);
		return Optional.ofNullable(doc).map(EventDocumentMapper::toDomain);
	}

	@Override
	public List<Event> findAfter(UUID sessionId, UUID lastEventId, int limit) {
		Criteria criteria = Criteria.where("sessionId").is(sessionId);
		if (lastEventId != null) {
			criteria.and("_id").gt(lastEventId);
		}
		Query query = new Query(criteria).with(Sort.by(Sort.Direction.ASC, "_id")).limit(limit);
		return mongoTemplate.find(query, EventDocument.class).stream()
				.map(EventDocumentMapper::toDomain).toList();
	}

	@Override
	public List<Event> findForReplay(UUID sessionId, UUID afterEventIdExclusive, Instant atInclusive) {
		Criteria criteria = Criteria.where("sessionId").is(sessionId);
		if (afterEventIdExclusive != null) {
			criteria.and("_id").gt(afterEventIdExclusive);
		}
		if (atInclusive != null) {
			criteria.and("serverTs").lte(atInclusive);
		}
		Query query = new Query(criteria).with(Sort.by(Sort.Direction.ASC, "_id"));
		return mongoTemplate.find(query, EventDocument.class).stream()
				.map(EventDocumentMapper::toDomain).toList();
	}

	@Override
	public List<Event> findRecentMessages(UUID sessionId, int limit) {
		Query query = new Query(Criteria.where("sessionId").is(sessionId)
				.and("type").is(EventType.MESSAGE_SENT))
				.with(Sort.by(Sort.Direction.DESC, "_id"))
				.limit(limit);
		return mongoTemplate.find(query, EventDocument.class).stream()
				.map(EventDocumentMapper::toDomain).toList();
	}

	@Override
	public Set<String> findJoinedUserIds(UUID sessionId) {
		Query query = new Query(Criteria.where("sessionId").is(sessionId)
				.and("type").is(EventType.PARTICIPANT_JOINED));
		return mongoTemplate.find(query, EventDocument.class).stream()
				.map(EventDocument::getActorUserId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	public long countAfter(UUID sessionId, UUID lastSnapshotEventId) {
		Criteria criteria = Criteria.where("sessionId").is(sessionId);
		if (lastSnapshotEventId != null) {
			criteria.and("_id").gt(lastSnapshotEventId);
		}
		return mongoTemplate.count(new Query(criteria), EventDocument.class);
	}
}
