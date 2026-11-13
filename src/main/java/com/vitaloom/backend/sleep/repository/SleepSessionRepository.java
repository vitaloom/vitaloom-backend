package com.vitaloom.backend.sleep.repository;

import com.vitaloom.backend.sleep.model.SleepSession;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class SleepSessionRepository {
    private final MongoTemplate mongo;

    public SleepSessionRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public SleepSession save(SleepSession session) {
        return mongo.save(session);
    }

    public SleepSession findByOwnerAndId(String owner, String id) {
        return mongo.findOne(Query.query(Criteria.where("owner").is(owner).and("_id").is(id)), SleepSession.class);
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
                .and("_id").ne(candidate.getId()).and("start_at").lt(candidate.getEndAt())
                .and("end_at").gt(candidate.getStartAt())), SleepSession.class);
    }

    public void delete(SleepSession session) {
        mongo.remove(session);
    }
}
