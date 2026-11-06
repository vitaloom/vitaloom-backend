package com.vitaloom.backend.heartrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.service.JwtTokenService;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
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
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/heart-rate/summary")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.latestRecorded").isEmpty());
    }
}
