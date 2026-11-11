package com.vitaloom.backend.heartrate.repository;

import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
        indexes.ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("account_id", Sort.Direction.ASC)
                .on("device_id", Sort.Direction.ASC).on("event_id", Sort.Direction.ASC).unique()
                .named("uk_hr_account_device_event"));
        indexes.ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("account_id", Sort.Direction.ASC)
                .on("occurred_at", Sort.Direction.ASC).named("ix_hr_account_occurred_at"));
    }

    public void insert(HeartRateMeasurement measurement) { mongo.insert(measurement); }

    public Optional<HeartRateMeasurement> findIdentity(String accountId, String deviceId, String eventId) {
        Query query = Query.query(Criteria.where("account_id").is(accountId).and("device_id").is(deviceId)
                .and("event_id").is(eventId));
        return Optional.ofNullable(mongo.findOne(query, HeartRateMeasurement.class));
    }

    public Optional<HeartRateMeasurement> latest(String accountId) {
        Query query = Query.query(Criteria.where("account_id").is(accountId))
                .with(Sort.by(Sort.Order.desc("occurred_at"), Sort.Order.asc("device_id"), Sort.Order.asc("event_id")))
                .limit(1);
        return Optional.ofNullable(mongo.findOne(query, HeartRateMeasurement.class));
    }

    public List<HeartRateMeasurement> findBetween(String accountId, Instant start, Instant end) {
        return mongo.find(rangeQuery(accountId, start, end), HeartRateMeasurement.class);
    }

    public Stream<HeartRateMeasurement> streamBetween(String accountId, Instant start, Instant end) {
        Query query = rangeQuery(accountId, start, end);
        query.fields().include("heart_rate_bpm").include("occurred_at");
        return mongo.stream(query, HeartRateMeasurement.class);
    }

    private static Query rangeQuery(String accountId, Instant start, Instant end) {
        return Query.query(Criteria.where("account_id").is(accountId).and("occurred_at").gte(start).lt(end));
    }
}
