package com.vitaloom.backend.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.auth.repository.UserRepository;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.steps.model.StepMeasurement;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import com.vitaloom.backend.water.model.WaterLog;
import com.vitaloom.backend.water.repository.WaterLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = "vitaloom.test.mongodb.database=persistence_test")
@Import({MongoPersistenceIntegrationTest.PersistenceTestConfig.class, MongoTestcontainersConfiguration.class})
class MongoPersistenceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-10T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-10T02:00:00Z");

    @Autowired MongoClient mongoClient;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired WaterLogRepository waterLogs;

    private final String suffix = UUID.randomUUID().toString();
    private final String accountId = "persistence-it-" + suffix;
    private final String waterUserId = "water-persistence-it-" + suffix;
    private final String otherWaterUserId = "water-persistence-other-it-" + suffix;
    private final String email = "persistence-it-" + suffix + "@example.test";

    @BeforeEach
    void setClockBeforeEachTest() {
        PersistenceTestConfig.CLOCK.setInstant(CREATED_AT);
    }

    @AfterEach
    void removeFixtures() {
        mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), HeartRateMeasurement.class);
        mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), StepMeasurement.class);
        mongo.remove(Query.query(Criteria.where("userId").in(waterUserId, otherWaterUserId)), WaterLog.class);
        mongo.remove(Query.query(Criteria.where("emailNormalized").is(email)), User.class);
    }

    @Test
    void persistsMappedBsonQueriesWithPhysicalIndexesAndAuditsOnlyUsers() {
        User user = users.save(new User(email, email, "encoded-password"));
        assertNotNull(user.getId());
        Document userBson = rawDocument("users", user.getId());
        assertBsonIdAndDate(userBson, "created_at", CREATED_AT);
        assertDate(userBson, "updated_at", CREATED_AT);
        assertEquals(email, userBson.getString("email"));
        assertEquals(email, userBson.getString("email_normalized"));
        assertEquals("encoded-password", userBson.getString("password_hash"));
        assertNull(userBson.get("emailNormalized"));
        assertEquals(user.getId(), users.findByEmailNormalized(email).orElseThrow().getId());

        PersistenceTestConfig.CLOCK.setInstant(UPDATED_AT);
        User updated = users.save(user);
        assertEquals(CREATED_AT, updated.getCreatedAt());
        assertEquals(UPDATED_AT, updated.getUpdatedAt());
        Document updatedUserBson = rawDocument("users", user.getId());
        assertBsonIdAndDate(updatedUserBson, "created_at", CREATED_AT);
        assertDate(updatedUserBson, "updated_at", UPDATED_AT);

        Instant start = Instant.parse("2026-11-10T00:00:00Z");
        Instant end = Instant.parse("2026-11-11T00:00:00Z");
        HeartRateMeasurement heartRate = new HeartRateMeasurement(accountId, "device", "hr-event", 72,
                start.plusSeconds(1), start.plusSeconds(2));
        StepMeasurement steps = new StepMeasurement(accountId, "device", "steps-event", 3,
                start.plusSeconds(3), start.plusSeconds(4));
        WaterLog before = new WaterLog(waterUserId, 100, start.minusSeconds(1));
        WaterLog first = new WaterLog(waterUserId, 200, start.plusSeconds(1));
        WaterLog second = new WaterLog(waterUserId, 300, start.plusSeconds(2));
        WaterLog endExclusive = new WaterLog(waterUserId, 400, end);
        WaterLog anotherUser = new WaterLog(otherWaterUserId, 500, start.plusSeconds(3));
        mongo.insert(heartRate);
        mongo.insert(steps);
        mongo.insert(List.of(before, first, second, endExclusive, anotherUser), WaterLog.class);

        Document heartRateBson = rawDocument("heart_rate_measurements", heartRate.getId());
        assertBsonIdAndDate(heartRateBson, "occurred_at", start.plusSeconds(1));
        assertDate(heartRateBson, "received_at", start.plusSeconds(2));
        assertEquals(accountId, heartRateBson.getString("account_id"));
        assertEquals("device", heartRateBson.getString("device_id"));
        assertEquals("hr-event", heartRateBson.getString("event_id"));
        assertEquals(72, heartRateBson.getInteger("heart_rate_bpm"));
        assertNoAuditFields(heartRateBson);

        Document stepsBson = rawDocument("step_measurements", steps.getId());
        assertBsonIdAndDate(stepsBson, "occurred_at", start.plusSeconds(3));
        assertDate(stepsBson, "received_at", start.plusSeconds(4));
        assertEquals(accountId, stepsBson.getString("account_id"));
        assertEquals("device", stepsBson.getString("device_id"));
        assertEquals("steps-event", stepsBson.getString("event_id"));
        assertEquals(3, stepsBson.getInteger("steps_delta"));
        assertNoAuditFields(stepsBson);

        Document waterBson = rawDocument("water_logs", first.getId());
        assertBsonIdAndDate(waterBson, "logged_at", start.plusSeconds(1));
        assertEquals(waterUserId, waterBson.getString("user_id"));
        assertEquals(200, waterBson.getInteger("amount_ml"));
        assertNoAuditFields(waterBson);

        List<WaterLog> inRange = waterLogs
                .findByUserIdAndLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(
                        waterUserId, start, end);
        assertEquals(List.of(300, 200), inRange.stream().map(WaterLog::getAmountMl).toList());

        assertIndex("heart_rate_measurements", "uk_hr_account_device_event",
                new Document("account_id", 1).append("device_id", 1).append("event_id", 1), true);
        assertIndex("heart_rate_measurements", "ix_hr_account_occurred_at",
                new Document("account_id", 1).append("occurred_at", 1), null);
        assertIndex("step_measurements", "uk_steps_account_device_event",
                new Document("account_id", 1).append("device_id", 1).append("event_id", 1), true);
        assertIndex("step_measurements", "ix_steps_account_occurred_at",
                new Document("account_id", 1).append("occurred_at", 1), null);
        assertIndex("water_logs", "user_logged_at_idx",
                new Document("user_id", 1).append("logged_at", -1), null);
        assertIndex("users", "emailNormalized", new Document("email_normalized", 1), true);
    }

    @Test
    void supportsTransactionsOnTheReplicaSet() {
        Document hello = mongo.getDb().runCommand(new Document("hello", 1));
        assertNotNull(hello.getString("setName"));
        assertEquals(true, hello.getBoolean("isWritablePrimary"));

        MongoCollection<Document> usersCollection = collection("users");
        Document userDocument = new Document("_id", new ObjectId()).append("email_normalized", email);
        try (var session = mongoClient.startSession()) {
            session.startTransaction();
            usersCollection.insertOne(session, userDocument);
            assertNotNull(usersCollection.find(session, new Document("_id", userDocument.getObjectId("_id"))).first());
            session.abortTransaction();
        }
        assertNull(usersCollection.find(new Document("_id", userDocument.getObjectId("_id"))).first());
    }

    private Document rawDocument(String collectionName, String id) {
        return collection(collectionName).find(new Document("_id", new ObjectId(id))).first();
    }

    private MongoCollection<Document> collection(String name) {
        return mongo.getDb().getCollection(name);
    }

    private void assertBsonIdAndDate(Document bson, String dateField, Instant expected) {
        assertNotNull(bson);
        assertInstanceOf(ObjectId.class, bson.get("_id"));
        assertDate(bson, dateField, expected);
    }

    private void assertDate(Document bson, String field, Instant expected) {
        assertEquals(Date.from(expected), bson.getDate(field));
    }

    private void assertNoAuditFields(Document bson) {
        assertFalse(bson.containsKey("created_at"));
        assertFalse(bson.containsKey("updated_at"));
    }

    private void assertIndex(String collectionName, String indexName, Document expectedKeys, Boolean unique) {
        List<Document> indexes = collection(collectionName).listIndexes().into(new ArrayList<>());
        Document index = indexes.stream()
                .filter(candidate -> indexName.equals(candidate.getString("name")))
                .findFirst().orElseThrow(() -> new AssertionError("Missing index " + indexName));
        assertEquals(expectedKeys, index.get("key"));
        if (unique != null) assertEquals(unique, index.getBoolean("unique", false));
    }

    @TestConfiguration
    static class PersistenceTestConfig {
        private static final AdjustableClock CLOCK = new AdjustableClock(CREATED_AT);

        @Bean
        @Primary
        Clock persistenceTestClock() { return CLOCK; }
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private AdjustableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        private void setInstant(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException("UTC only");
            return this;
        }
        @Override public Instant instant() { return instant.get(); }
    }
}
