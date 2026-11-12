package com.vitaloom.backend.steps.goal.repository;

import com.vitaloom.backend.steps.goal.model.StepGoalSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class StepGoalRepository {
    private final MongoTemplate mongo;

    public StepGoalRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        mongo.indexOps(StepGoalSnapshot.class).ensureIndex(new Index()
                .on("account_id", Sort.Direction.ASC).on("effective_date", Sort.Direction.ASC)
                .unique().named("uk_step_goal_account_effective_date"));
    }

    public void upsertSnapshot(String accountId, LocalDate effectiveDate, Long targetSteps, boolean removed) {
        Query query = identity(accountId, effectiveDate);
        Update update = new Update().set("target_steps", targetSteps).set("removed", removed)
                .setOnInsert("account_id", accountId)
                .setOnInsert("effective_date", effectiveDate.toString());
        try {
            mongo.upsert(query, update, StepGoalSnapshot.class);
        } catch (DuplicateKeyException concurrentInsert) {
            if (mongo.updateFirst(query, update, StepGoalSnapshot.class).getMatchedCount() == 0) {
                throw concurrentInsert;
            }
        }
    }

    public Optional<StepGoalSnapshot> latestOnOrBefore(String accountId, LocalDate date) {
        Query query = Query.query(Criteria.where("account_id").is(accountId)
                        .and("effective_date").lte(date.toString()))
                .with(Sort.by(Sort.Order.desc("effective_date"))).limit(1);
        return Optional.ofNullable(mongo.findOne(query, StepGoalSnapshot.class));
    }

    private static Query identity(String accountId, LocalDate effectiveDate) {
        return Query.query(Criteria.where("account_id").is(accountId)
                .and("effective_date").is(effectiveDate.toString()));
    }
}
