package com.vitaloom.backend.sleep.repository;

import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class SleepAiConsentRepository implements ConsentStore {
    private static final String COLLECTION = "sleep_ai_consent";
    private final MongoTemplate mongo;

    public SleepAiConsentRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Consent read(String owner) {
        var stored = mongo.findById(owner, StoredConsent.class, COLLECTION);
        return stored == null ? new Consent(false, "") : new Consent(stored.enabled(), stored.revision());
    }

    @Override
    public void write(String owner, boolean enabled) {
        mongo.upsert(Query.query(Criteria.where("_id").is(owner)),
                new Update().set("enabled", enabled).set("revision", UUID.randomUUID().toString()),
                StoredConsent.class, COLLECTION);
    }

    private record StoredConsent(@MongoId String owner, boolean enabled, String revision) {}
}
