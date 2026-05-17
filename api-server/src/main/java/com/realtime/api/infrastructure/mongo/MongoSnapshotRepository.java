package com.realtime.api.infrastructure.mongo;

import com.realtime.common.domain.session.Snapshot;
import com.realtime.common.domain.session.SnapshotRepository;
import com.realtime.common.infrastructure.mongo.SnapshotDocument;
import com.realtime.common.infrastructure.mongo.SnapshotDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** api-server 측 {@link SnapshotRepository} 구현(MongoDB). */
@Repository
@RequiredArgsConstructor
public class MongoSnapshotRepository implements SnapshotRepository {

	private final MongoTemplate mongoTemplate;

	@Override
	public Snapshot save(Snapshot snapshot) {
		mongoTemplate.save(SnapshotDocumentMapper.toDocument(snapshot));
		return snapshot;
	}

	@Override
	public Optional<Snapshot> findLatestBefore(UUID sessionId, Instant at) {
		Query query = new Query(Criteria.where("sessionId").is(sessionId)
				.and("snapshotAt").lte(at))
				.with(Sort.by(Sort.Direction.DESC, "snapshotAt"))
				.limit(1);
		SnapshotDocument doc = mongoTemplate.findOne(query, SnapshotDocument.class);
		return Optional.ofNullable(doc).map(SnapshotDocumentMapper::toDomain);
	}

	@Override
	public Optional<Snapshot> findLatest(UUID sessionId) {
		Query query = new Query(Criteria.where("sessionId").is(sessionId))
				.with(Sort.by(Sort.Direction.DESC, "snapshotAt"))
				.limit(1);
		SnapshotDocument doc = mongoTemplate.findOne(query, SnapshotDocument.class);
		return Optional.ofNullable(doc).map(SnapshotDocumentMapper::toDomain);
	}
}
