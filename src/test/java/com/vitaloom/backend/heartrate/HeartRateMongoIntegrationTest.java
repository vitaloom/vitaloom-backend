package com.vitaloom.backend.heartrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = "vitaloom.test.mongodb.database=heartrate_test")
@AutoConfigureMockMvc
@Import(MongoTestcontainersConfiguration.class)
class HeartRateMongoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired MongoTemplate mongo;
    private final List<String> testAccounts = new ArrayList<>();

    @AfterEach
    void removeTestMeasurements() {
        testAccounts.forEach(accountId -> mongo.remove(
                Query.query(Criteria.where("accountId").is(accountId)), HeartRateMeasurement.class));
    }

    @Test
    void uniqueIndexMakesConcurrentNanosecondRetriesIdempotentAndAccountScoped() throws Exception {
        String accountId = "hr-it-" + UUID.randomUUID();
        testAccounts.add(accountId);
        String token = tokens.issueAccess(accountId, Instant.now());
        String timestamp = "2026-11-05T11:11:11.123456789Z";
        String request = """
                {"accountId":"attacker-controlled","deviceId":"integration-device","runId":"integration-run",
                 "events":[{"eventId":"same-event","occurredAt":"%s","heartRateBpm":81}]}
                """.formatted(timestamp);
        int concurrentRequests = 8;
        var ready = new CountDownLatch(concurrentRequests);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrentRequests)) {
            List<java.util.concurrent.Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(post("/api/heart-rate/batch")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                            .andExpect(status().isOk()).andReturn();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int accepted = 0, duplicates = 0;
            for (var future : futures) {
                String body = future.get(10, TimeUnit.SECONDS).getResponse().getContentAsString();
                if (body.contains("\"status\":\"ACCEPTED\"")) accepted++;
                if (body.contains("\"status\":\"DUPLICATE\"")) duplicates++;
            }
            assertEquals(1, accepted);
            assertEquals(concurrentRequests - 1, duplicates);
        }

        long persisted = mongo.count(Query.query(Criteria.where("accountId").is(accountId)), HeartRateMeasurement.class);
        assertEquals(1, persisted);
        String otherToken = tokens.issueAccess("hr-it-" + UUID.randomUUID(), Instant.now());
        mvc.perform(get("/api/statistics/heart-rate/summary")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.latestRecorded").isEmpty());
    }

    @Test
    void dailyTimeSeriesStreamsOwnedSamplesAndKeepsHalfOpenBoundaries() throws Exception {
        String accountId = "hr-series-it-" + UUID.randomUUID();
        String otherAccountId = "hr-series-other-" + UUID.randomUUID();
        testAccounts.add(accountId);
        testAccounts.add(otherAccountId);
        Instant dayStart = Instant.parse("2026-11-04T17:00:00Z");
        Instant dayEnd = Instant.parse("2026-11-05T17:00:00Z");
        mongo.insert(new HeartRateMeasurement(accountId, "device-a", "at-start-a", 60, dayStart, dayStart));
        mongo.insert(new HeartRateMeasurement(accountId, "device-b", "at-start-b", 80, dayStart, dayStart));
        mongo.insert(new HeartRateMeasurement(accountId, "device-a", "at-end", 100, dayEnd, dayEnd));
        mongo.insert(new HeartRateMeasurement(otherAccountId, "device-a", "other-account", 120, dayStart, dayStart));

        String token = tokens.issueAccess(accountId, Instant.now());
        mvc.perform(get("/api/statistics/heart-rate/daily")
                        .queryParam("date", "2026-11-05")
                        .queryParam("accountId", otherAccountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2026-11-04T17:00:00Z"))
                .andExpect(jsonPath("$.endAt").value("2026-11-05T17:00:00Z"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.intervalMinutes").value(15))
                .andExpect(jsonPath("$.buckets.length()").value(96))
                .andExpect(jsonPath("$.buckets[0].minBpm").value(60))
                .andExpect(jsonPath("$.buckets[0].avgBpm").value(70.0))
                .andExpect(jsonPath("$.buckets[0].maxBpm").value(80))
                .andExpect(jsonPath("$.buckets[0].sampleCount").value(2))
                .andExpect(jsonPath("$.buckets[1].minBpm").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.buckets[1].avgBpm").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.buckets[1].maxBpm").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.buckets[1].sampleCount").value(0));
    }

    @Test
    void allTimeSeriesRoutesRequireBearerAuthentication() throws Exception {
        for (String route : List.of("/api/statistics/heart-rate/daily",
                "/api/statistics/heart-rate/weekly", "/api/statistics/heart-rate/custom")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            mvc.perform(get(route).header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void malformedTimeSeriesDateUsesStatisticsErrorEnvelope() throws Exception {
        String token = tokens.issueAccess("hr-series-invalid-" + UUID.randomUUID(), Instant.now());
        mvc.perform(get("/api/statistics/heart-rate/daily").queryParam("date", "2026-11-6")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT_DATE"));
        mvc.perform(get("/api/statistics/heart-rate/custom").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }
}
