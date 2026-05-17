package com.realtime.common.infrastructure.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

public interface EventMongoRepository extends MongoRepository<EventDocument, UUID> {
}
