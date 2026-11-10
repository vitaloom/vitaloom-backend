package com.vitaloom.backend.heartrate.repository;

import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class HeartRateRepository {
    private final MongoTemplate mongo;

    public HeartRateRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        IndexOperations indexes = mongo.indexOps(HeartRateMeasurement.class);
        indexes.ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("accountId", Sort.Direction.ASC)
                .on("deviceId", Sort.Direction.ASC).on("eventId", Sort.Direction.ASC).unique()
                .named("uk_hr_account_device_event"));
        indexes.ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("accountId", Sort.Direction.ASC)
                .on("occurredAt", Sort.Direction.ASC).named("ix_hr_account_occurred_at"));
    }

    public void insert(HeartRateMeasurement measurement) { mongo.insert(measurement); }

    public Optional<HeartRateMeasurement> findIdentity(String accountId, String deviceId, String eventId) {
        Query query = Query.query(Criteria.where("accountId").is(accountId).and("deviceId").is(deviceId)
                .and("eventId").is(eventId));
        return Optional.ofNullable(mongo.findOne(query, HeartRateMeasurement.class));
    }

    public Optional<HeartRateMeasurement> latest(String accountId) {
        Query query = Query.query(Criteria.where("accountId").is(accountId))
                .with(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.asc("deviceId"), Sort.Order.asc("eventId")))
                .limit(1);
        return Optional.ofNullable(mongo.findOne(query, HeartRateMeasurement.class));
    }

    public List<HeartRateMeasurement> findBetween(String accountId, Instant start, Instant end) {
        Query query = Query.query(Criteria.where("accountId").is(accountId).and("occurredAt").gte(start).lt(end));
        return mongo.find(query, HeartRateMeasurement.class);
    }
}
