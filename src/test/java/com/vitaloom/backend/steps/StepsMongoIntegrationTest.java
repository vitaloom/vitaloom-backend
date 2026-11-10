package com.vitaloom.backend.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.steps.model.StepMeasurement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StepsMongoIntegrationTest.FixedClockConfig.class)
class StepsMongoIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-10T01:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired MongoTemplate mongo;
    private final List<String> testAccounts = new ArrayList<>();

    @AfterEach
    void removeTestMeasurements() {
        testAccounts.forEach(accountId -> mongo.remove(
                Query.query(Criteria.where("accountId").is(accountId)), StepMeasurement.class));
    }

    @Test
    void mixedBatchPersistsAcceptedRowsAndReturnsOrderedPartialResults() throws Exception {
        String accountId = account("mixed");
        String token = token(accountId);
        String request = """
                {"deviceId":"device","runId":"run","events":[
                 {"eventId":"one","occurredAt":"2026-11-10T00:00:00Z","stepsDelta":1},
                 {"eventId":"five","occurredAt":"2026-11-10T00:00:01Z","stepsDelta":5},
                 {"eventId":"one","occurredAt":"2026-11-10T00:00:00.000Z","stepsDelta":1},
                 {"eventId":"zero","occurredAt":"2026-11-10T00:00:02Z","stepsDelta":0},
                 {"eventId":"six","occurredAt":"2026-11-10T00:00:03Z","stepsDelta":6},
                 {"eventId":"future","occurredAt":"2026-11-10T01:00:00.001Z","stepsDelta":2},
                 {"eventId":"one","occurredAt":"2026-11-10T00:00:00Z","stepsDelta":2}]}
                """;

        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run"))
                .andExpect(jsonPath("$.received").value(7))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected").value(4))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[2].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[3].error.code").value("INVALID_STEPS_DELTA"))
                .andExpect(jsonPath("$.results[4].error.code").value("INVALID_STEPS_DELTA"))
                .andExpect(jsonPath("$.results[5].error.code").value("FUTURE_OCCURRED_AT"))
                .andExpect(jsonPath("$.results[6].error.code").value("EVENT_ID_CONFLICT"));
        assertEquals(2, count(accountId));
    }

    @Test
    void invalidEnvelopesAreRejectedAndAuthenticationProtectsAllStepsRoutes() throws Exception {
        String empty = "{\"deviceId\":\"device\",\"runId\":\"run\",\"events\":[]}";
        String oversized = "{\"deviceId\":\"device\",\"runId\":\"run\",\"events\":["
                + String.join(",", java.util.Collections.nCopies(501,
                "{\"eventId\":\"id\",\"occurredAt\":\"2026-11-10T00:00:00Z\",\"stepsDelta\":1}")) + "]}";
        mvc.perform(post("/api/steps/batch").contentType(MediaType.APPLICATION_JSON).content(empty))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + token(account("invalid")))
                        .contentType(MediaType.APPLICATION_JSON).content(empty))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + token(account("oversized")))
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/daily")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/statistics/steps/weekly")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/statistics/steps/custom").queryParam("startDate", "2026-11-08")
                .queryParam("endDate", "2026-11-09")).andExpect(status().isUnauthorized());
    }

    @Test
    void uniqueIndexKeepsConcurrentMillisecondRetriesIdempotentAndScopesIdentityByAccount() throws Exception {
        String accountA = account("concurrent-a");
        String accountB = account("concurrent-b");
        String tokenA = token(accountA);
        String tokenB = token(accountB);
        String payload = "{\"deviceId\":\"same-device\",\"runId\":\"run-one\",\"events\":["
                + "{\"eventId\":\"shared-id\",\"occurredAt\":\"2026-11-10T00:00:00.123456789Z\",\"stepsDelta\":3}]}";
        int concurrentRequests = 6;
        var ready = new CountDownLatch(concurrentRequests);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrentRequests)) {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + tokenA)
                                    .contentType(MediaType.APPLICATION_JSON).content(payload))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            int duplicates = 0;
            for (var future : futures) {
                String body = future.get(10, TimeUnit.SECONDS);
                if (body.contains("\"status\":\"ACCEPTED\"")) accepted++;
                if (body.contains("\"status\":\"DUPLICATE\"")) duplicates++;
            }
            assertEquals(1, accepted);
            assertEquals(concurrentRequests - 1, duplicates);
        }
        assertEquals(1, count(accountA));

        String retryWithNewRunId = payload.replace("run-one", "run-two")
                .replace("123456789Z", "123999999Z");
        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(retryWithNewRunId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicates").value(1));
        String conflict = payload.replace("123456789Z", "124456789Z");
        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].error.code").value("EVENT_ID_CONFLICT"));
        mvc.perform(post("/api/steps/batch").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        assertEquals(1, count(accountB));

        List<IndexInfo> indexes = mongo.indexOps(StepMeasurement.class).getIndexInfo();
        assertTrue(indexes.stream().anyMatch(index -> index.getName().equals("uk_steps_account_device_event")
                && index.isUnique()));
        assertTrue(indexes.stream().anyMatch(index -> index.getName().equals("ix_steps_account_occurred_at")));
    }

    @Test
    void reportRangesRespectLocalMidnightAccountScopeAndEmptyBuckets() throws Exception {
        String accountA = account("range-a");
        String accountB = account("range-b");
        mongo.insert(new StepMeasurement(accountA, "dev", "sunday", 5,
                Instant.parse("2026-11-08T16:59:59.999Z"), NOW));
        mongo.insert(new StepMeasurement(accountA, "dev", "monday-start", 2,
                Instant.parse("2026-11-08T17:00:00Z"), NOW));
        mongo.insert(new StepMeasurement(accountA, "dev", "monday-late", 3,
                Instant.parse("2026-11-08T19:00:00Z"), NOW));
        mongo.insert(new StepMeasurement(accountB, "dev", "foreign", 40,
                Instant.parse("2026-11-08T18:00:00Z"), NOW));
        String token = token(accountA);

        mvc.perform(get("/api/statistics/steps/daily").queryParam("date", "2026-11-09")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2026-11-08T17:00:00Z"))
                .andExpect(jsonPath("$.endAt").value("2026-11-09T17:00:00Z"))
                .andExpect(jsonPath("$.bucketSizeMinutes").value(60))
                .andExpect(jsonPath("$.totalSteps").value(5))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.buckets[0].steps").value(2))
                .andExpect(jsonPath("$.buckets[2].steps").value(3))
                .andExpect(jsonPath("$.buckets[0].eventCount").value(1));

        mvc.perform(get("/api/statistics/steps/weekly").queryParam("weekStartDate", "2026-11-09")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketSizeMinutes").value(1440))
                .andExpect(jsonPath("$.buckets.length()").value(7))
                .andExpect(jsonPath("$.totalSteps").value(5))
                .andExpect(jsonPath("$.buckets[0].steps").value(5))
                .andExpect(jsonPath("$.buckets[0].eventCount").value(2))
                .andExpect(jsonPath("$.buckets[1].steps").doesNotExist());

        mvc.perform(get("/api/statistics/steps/custom").queryParam("startDate", "2026-11-08")
                        .queryParam("endDate", "2026-11-09").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSteps").value(10))
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].steps").value(5))
                .andExpect(jsonPath("$.buckets[1].steps").value(5));

        String emptyToken = token(account("empty"));
        mvc.perform(get("/api/statistics/steps/daily").queryParam("date", "2026-11-09")
                        .header("Authorization", "Bearer " + emptyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSteps").doesNotExist())
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.buckets[0].steps").doesNotExist())
                .andExpect(jsonPath("$.buckets[0].eventCount").value(0));
    }

    @Test
    void reportDateValidationRejectsFutureMalformedAndInvalidRanges() throws Exception {
        String token = token(account("date-validation"));
        mvc.perform(get("/api/statistics/steps/daily").queryParam("date", "2026-11-11")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/daily").queryParam("date", "2026-11-6")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/weekly").queryParam("weekStartDate", "2026-11-10")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/weekly").queryParam("weekStartDate", "2026-11-16")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/custom").queryParam("startDate", "2026-11-10")
                        .queryParam("endDate", "2026-11-09").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/statistics/steps/custom").queryParam("startDate", "2026-10-11")
                        .queryParam("endDate", "2026-11-09").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buckets.length()").value(30));
        mvc.perform(get("/api/statistics/steps/custom").queryParam("startDate", "2026-10-10")
                        .queryParam("endDate", "2026-11-09").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private String account(String prefix) {
        String accountId = "steps-it-" + prefix + "-" + UUID.randomUUID();
        testAccounts.add(accountId);
        return accountId;
    }

    private String token(String accountId) { return tokens.issueAccess(accountId, Instant.now()); }

    private long count(String accountId) {
        return mongo.count(Query.query(Criteria.where("accountId").is(accountId)), StepMeasurement.class);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedStepsTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
