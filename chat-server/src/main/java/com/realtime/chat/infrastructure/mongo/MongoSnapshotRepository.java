package com.realtime.chat.infrastructure.mongo;

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

/**
 * chat-server 측 {@link SnapshotRepository} 구현(MongoDB).
 *
 * <p>chat-server는 주로 resume 시 "임계치 초과 또는 last_event_id 부재" 경로에서 가장 최신 스냅샷을
 * 가져온다(§9.3). 스냅샷 생성은 api-server가 담당하므로 {@code save}는 호출되지 않을 가능성이 높지만
 * 인터페이스 충족을 위해 구현해 둔다.
 */
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
