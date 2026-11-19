package com.vitaloom.backend.assistant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
final class AssistantAccountSettings {
    static final String CONSENT_VERSION = "gemini-health-v1";
    private final MongoTemplate mongo;

    AssistantAccountSettings(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    Preference get(String owner) {
        var stored = mongo.findById(owner, Preference.class);
        return stored == null ? new Preference(owner, false, 0, null) : stored;
    }

    Preference requireEnabled(String owner) {
        Preference preference = get(owner);
        if (!preference.personalDataAllowed()) {
            throw new AssistantAccountSettingsController.AccountAccessRequiredException();
        }
        return preference;
    }

    Preference set(String owner, boolean allowed) {
        var updated = mongo.findAndModify(Query.query(Criteria.where("id").is(owner)),
                new Update().set("personalDataAllowed", allowed)
                        .set("consentVersion", allowed ? CONSENT_VERSION : null).inc("generation", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true), Preference.class);
        return updated;
    }

    boolean isCurrent(String owner, Preference expected) {
        return expected.personalDataAllowed() && mongo.exists(Query.query(Criteria.where("id").is(owner)
                .and("personalDataAllowed").is(true).and("consentVersion").is(CONSENT_VERSION)
                .and("generation").is(expected.generation())), Preference.class);
    }

    boolean isUnchanged(String owner, Preference expected) {
        var current = get(owner);
        return current.personalDataAllowed() == expected.personalDataAllowed()
                && current.generation() == expected.generation();
    }

    @Document("assistant_account_settings")
    record Preference(@Id String id, boolean personalDataAllowed, long generation, String consentVersion) {
        @Override
        public boolean personalDataAllowed() {
            return personalDataAllowed && CONSENT_VERSION.equals(consentVersion);
        }
    }
}
