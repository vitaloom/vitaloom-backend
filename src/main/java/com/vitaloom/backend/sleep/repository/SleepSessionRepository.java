package com.vitaloom.backend.sleep.repository;

import com.vitaloom.backend.sleep.model.SleepSession;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class SleepSessionRepository {
    private final MongoTemplate mongo;

    public SleepSessionRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        IndexOperations indexes = mongo.indexOps(SleepSession.class);
        indexes.createIndex(new Index().on("owner", Sort.Direction.ASC)
                .on("statistical_day", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("sleep_type").is("MAIN")))
                .named("uk_sleep_owner_main_day"));
    }

    public boolean replaceIfConfirmedAt(SleepSession session, Instant expectedConfirmedAt) {
        Query query = Query.query(Criteria.where("owner").is(session.getOwner())
                .and("_id").is(session.getId()).and("confirmed_at").is(expectedConfirmedAt));
        Update update = new Update()
                .set("sleep_type", session.getSleepType())
                .set("method", session.getMethod())
                .set("minutes", session.getMinutes())
                .set("statistical_day", session.getDay())
                .set("feeling", session.getFeeling())
                .set("confirmed_at", session.getConfirmedAt())
                .set("time_zone", session.getTimeZone())
                .set("start", session.getStart())
                .set("end", session.getEnd())
                .set("start_at", session.getStartAt())
                .set("end_at", session.getEndAt());
        return mongo.updateFirst(query, update, SleepSession.class).getMatchedCount() == 1;
    }

    public SleepSession insert(SleepSession session) {
        return mongo.insert(session);
    }

    public SleepSession findByOwnerAndId(String owner, String id) {
        return mongo.findOne(Query.query(Criteria.where("owner").is(owner).and("_id").is(id)), SleepSession.class);
    }

    public String latestMainDay(String owner) {
        Query query = Query.query(Criteria.where("owner").is(owner).and("sleep_type").is("MAIN"))
                .with(Sort.by(Sort.Order.desc("statistical_day"))).limit(1);
        SleepSession session = mongo.findOne(query, SleepSession.class);
        return session == null ? null : session.getDay();
    }

    public boolean existsMainOnDay(String owner, String day, String excludedId) {
        Criteria criteria = Criteria.where("owner").is(owner).and("sleep_type").is("MAIN")
                .and("statistical_day").is(day);
        if (excludedId != null) criteria.and("_id").ne(excludedId);
        return mongo.exists(Query.query(criteria), SleepSession.class);
    }

    public List<SleepSession> findByOwner(String owner, String from, String to) {
        Query query = Query.query(Criteria.where("owner").is(owner));
        if (from != null || to != null) {
            Criteria day = Criteria.where("statistical_day");
            if (from != null) day.gte(from);
            if (to != null) day.lte(to);
            query.addCriteria(day);
        }
        return mongo.find(query.with(Sort.by(Sort.Order.desc("statistical_day"), Sort.Order.asc("_id"))),
                SleepSession.class);
    }

    public boolean existsRangeOverlap(String owner, SleepSession candidate) {
        return mongo.exists(Query.query(Criteria.where("owner").is(owner)
                .and("sleep_type").is("MAIN").and("_id").ne(candidate.getId())
                .and("start_at").lt(candidate.getEndAt())
                .and("end_at").gt(candidate.getStartAt())), SleepSession.class);
    }

    public boolean deleteIfConfirmedAt(SleepSession session, Instant expectedConfirmedAt) {
        Query query = Query.query(Criteria.where("owner").is(session.getOwner())
                .and("_id").is(session.getId()).and("confirmed_at").is(expectedConfirmedAt));
        return mongo.remove(query, SleepSession.class).getDeletedCount() == 1;
    }
}
