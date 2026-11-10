package com.vitaloom.backend.steps.repository;

import com.vitaloom.backend.steps.model.StepMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class StepsRepository {
    private final MongoTemplate mongo;

    public StepsRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        var indexes = mongo.indexOps(StepMeasurement.class);
        indexes.ensureIndex(new Index().on("accountId", Sort.Direction.ASC).on("deviceId", Sort.Direction.ASC)
                .on("eventId", Sort.Direction.ASC).unique().named("uk_steps_account_device_event"));
        indexes.ensureIndex(new Index().on("accountId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.ASC)
                .named("ix_steps_account_occurred_at"));
    }

    public void insert(StepMeasurement measurement) { mongo.insert(measurement); }

    public Optional<StepMeasurement> findIdentity(String accountId, String deviceId, String eventId) {
        Query query = Query.query(Criteria.where("accountId").is(accountId).and("deviceId").is(deviceId)
                .and("eventId").is(eventId));
        return Optional.ofNullable(mongo.findOne(query, StepMeasurement.class));
    }

    public List<StepMeasurement> findBetween(String accountId, Instant start, Instant end) {
        Query query = Query.query(Criteria.where("accountId").is(accountId)
                .and("occurredAt").gte(start).lt(end));
        return mongo.find(query, StepMeasurement.class);
    }
}
