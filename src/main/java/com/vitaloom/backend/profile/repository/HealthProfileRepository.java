package com.vitaloom.backend.profile.repository;

import java.util.Map;

import com.vitaloom.backend.auth.model.User;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class HealthProfileRepository {
    private final MongoTemplate mongo;

    public HealthProfileRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public User findUser(String userId) { return mongo.findById(userId, User.class); }

    public User createIfAbsent(String userId, Map<String, Object> profile) {
        Query query = Query.query(Criteria.where("id").is(userId).and("health_profile").exists(false));
        return mongo.findAndModify(query, new Update().set("health_profile", profile)
                .set("profile_invitation", "completed"), FindAndModifyOptions.options().returnNew(true), User.class);
    }

    public User updateIfRevision(String userId, String expectedRevision, Map<String, Object> profile) {
        Criteria criteria = Criteria.where("id").is(userId);
        if ("0".equals(expectedRevision)) criteria.and("health_profile").exists(false);
        else criteria.and("health_profile.revision").is(expectedRevision);
        return mongo.findAndModify(Query.query(criteria), new Update().set("health_profile", profile)
                .set("profile_invitation", "completed"), FindAndModifyOptions.options().returnNew(true), User.class);
    }

    public String decide(String userId, String decision) {
        Query query = Query.query(Criteria.where("id").is(userId));
        if ("completed".equals(decision)) query.addCriteria(Criteria.where("health_profile").exists(true));
        else query.addCriteria(Criteria.where("profile_invitation").ne("completed"));
        mongo.updateFirst(query, new Update().set("profile_invitation", decision), User.class);
        User user = findUser(userId);
        return user == null ? null : user.getProfileInvitation();
    }
}
