package com.realtime.common.infrastructure.mongo;

import com.realtime.common.domain.session.Session;
import com.realtime.common.domain.session.SessionRepository;
import com.realtime.common.domain.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MongoSessionRepository implements SessionRepository {

	private final MongoTemplate mongoTemplate;

	@Override
	public Session save(Session session) {
		SessionDocument doc = new SessionDocument(
				session.id(),
				session.status(),
				session.createdBy(),
				session.createdAt(),
				session.endedAt()
		);
		mongoTemplate.save(doc);
		return session;
	}

	@Override
	public Optional<Session> findById(UUID id) {
		SessionDocument doc = mongoTemplate.findById(id, SessionDocument.class);
		if (doc == null) return Optional.empty();
		return Optional.of(Session.reconstitute(
				doc.getId(), doc.getCreatedBy(), doc.getCreatedAt(), doc.getStatus(), doc.getEndedAt()));
	}

	@Override
	public List<Session> findByFilter(SessionStatus status, String participantUserId,
									  Instant from, Instant to, UUID cursorId, int limit) {
		Criteria criteria = new Criteria();
		if (status != null) criteria.and("status").is(status);
		if (participantUserId != null) criteria.and("createdBy").is(participantUserId);
		if (from != null || to != null) {
			Criteria range = Criteria.where("createdAt");
			if (from != null) range.gte(from);
			if (to != null) range.lte(to);
			criteria.andOperator(range);
		}
		if (cursorId != null) criteria.and("_id").gt(cursorId);

		Query query = new Query(criteria).with(Sort.by(Sort.Direction.ASC, "_id")).limit(limit);
		return mongoTemplate.find(query, SessionDocument.class).stream()
				.map(doc -> Session.reconstitute(
						doc.getId(), doc.getCreatedBy(), doc.getCreatedAt(),
						doc.getStatus(), doc.getEndedAt()))
				.toList();
	}
}
