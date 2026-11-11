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
        indexes.ensureIndex(new Index().on("account_id", Sort.Direction.ASC).on("device_id", Sort.Direction.ASC)
                .on("event_id", Sort.Direction.ASC).unique().named("uk_steps_account_device_event"));
        indexes.ensureIndex(new Index().on("account_id", Sort.Direction.ASC).on("occurred_at", Sort.Direction.ASC)
                .named("ix_steps_account_occurred_at"));
    }

    public void insert(StepMeasurement measurement) { mongo.insert(measurement); }

    public Optional<StepMeasurement> findIdentity(String accountId, String deviceId, String eventId) {
        Query query = Query.query(Criteria.where("account_id").is(accountId).and("device_id").is(deviceId)
                .and("event_id").is(eventId));
        return Optional.ofNullable(mongo.findOne(query, StepMeasurement.class));
    }

    public List<StepMeasurement> findBetween(String accountId, Instant start, Instant end) {
        Query query = Query.query(Criteria.where("account_id").is(accountId)
                .and("occurred_at").gte(start).lt(end));
        return mongo.find(query, StepMeasurement.class);
    }
}
