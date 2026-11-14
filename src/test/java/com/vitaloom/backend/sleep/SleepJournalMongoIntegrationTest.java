package com.vitaloom.backend.sleep;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import com.vitaloom.backend.sleep.model.SleepSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = "vitaloom.test.mongodb.database=sleep_test")
@AutoConfigureMockMvc
@Import({SleepJournalMongoIntegrationTest.SleepClockConfig.class, MongoTestcontainersConfiguration.class})
class SleepJournalMongoIntegrationTest {
    private static final String SESSIONS = "/api/sleep/v2/sessions";
    private static final Instant FRIDAY = Instant.parse("2026-11-13T12:00:00Z");
    private static final String DURATION = """
            {"sleepType":"MAIN","method":"DURATION","endDay":"2026-11-13","minutes":1}
            """;
    private static final String RANGE = """
            {"sleepType":"MAIN","method":"RANGE","timeZone":"UTC",
             "start":{"day":"2026-11-13","hour":"01","minute":"00"},
             "end":{"day":"2026-11-13","hour":"02","minute":"00"}}
            """;

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired JwtTokenService tokens;
    @Autowired SleepClock clock;
    @LocalServerPort int port;
    private final String owner = UUID.randomUUID().toString();
    private final String stranger = UUID.randomUUID().toString();

    @BeforeEach
    void resetClock() {
        clock.now = FRIDAY;
    }

    @AfterEach
    void cleanUp() {
        mongo.remove(Query.query(Criteria.where("owner").in(owner, stranger)), "sleep_sessions");
    }

    @Test
    void allSleepRoutesRequireAuthentication() throws Exception {
        mvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
        mvc.perform(post(SESSIONS)).andExpect(status().isUnauthorized());
        mvc.perform(get(SESSIONS + "/missing")).andExpect(status().isUnauthorized());
        mvc.perform(put(SESSIONS + "/missing")).andExpect(status().isUnauthorized());
        mvc.perform(delete(SESSIONS + "/missing")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanCreateListReadUpdateAndDeleteWhileOtherAccountsCannotAccessRecords() throws Exception {
        mvc.perform(create(owner, DURATION)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty()).andExpect(jsonPath("$.sleepType").value("MAIN"))
                .andExpect(jsonPath("$.method").value("DURATION")).andExpect(jsonPath("$.minutes").value(1))
                .andExpect(jsonPath("$.day").value("2026-11-13"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.startAt").doesNotHaveJsonPath());
        String id = idOn("2026-11-13");

        mvc.perform(create(owner, RANGE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_DAY_ALREADY_RECORDED"));
        mvc.perform(create(owner, durationOn("2026-11-12"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(auth(get(SESSIONS), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2));
        mvc.perform(auth(get(SESSIONS).param("from", "2026-11-12").param("to", "2026-11-12"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].editable").value(false));
        mvc.perform(auth(get(SESSIONS).param("from", "2026-11-14"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions").isEmpty());
        mvc.perform(auth(get(SESSIONS + "/" + id), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(1));
        mvc.perform(auth(get(SESSIONS), stranger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions").isEmpty());
        mvc.perform(auth(get(SESSIONS + "/" + id), stranger)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SLEEP_NOT_FOUND"));
        mvc.perform(auth(put(SESSIONS + "/" + id), stranger).contentType(MediaType.APPLICATION_JSON).content(RANGE))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(SESSIONS + "/" + id), stranger)).andExpect(status().isNotFound());

        mvc.perform(update(id, DURATION.replace("\"minutes\":1", "\"minutes\":90")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sleepType").value("MAIN"))
                .andExpect(jsonPath("$.minutes").value(90));
        mvc.perform(auth(delete(SESSIONS + "/" + id), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(get(SESSIONS + "/" + id), owner)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SLEEP_NOT_FOUND"));
        mvc.perform(auth(get(SESSIONS + "/" + idOn("2026-11-12")), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void invalidDurationAndWrongModeFieldsAreRejected() throws Exception {
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":0")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SLEEP_VALIDATION"));
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":1.5")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":1441")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, DURATION.replace("\"method\":\"DURATION\"", "\"method\":\"DURATION\",\"timeZone\":\"UTC\"")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, durationOn("2026-11-14")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, RANGE.replace("\"hour\":\"02\"", "\"hour\":\"13\"")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void httpCreateAndListPersistTheSessionInMongo() throws Exception {
        String authorization = "Bearer " + tokens.issueAccess(owner, Instant.now());
        URI sessionsUrl = URI.create("http://localhost:" + port + SESSIONS);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest create = HttpRequest.newBuilder(sessionsUrl)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DURATION)).build();
            HttpResponse<String> created = client.send(create, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            String id = idOn("2026-11-13");

            HttpRequest detail = HttpRequest.newBuilder(URI.create(sessionsUrl + "/" + id))
                    .header("Authorization", authorization).GET().build();
            HttpResponse<String> response = client.send(detail, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"minutes\":1"));
        }
    }

    @Test
    void rangeRecordsKeepReferenceTimezoneAndCanConvertToDuration() throws Exception {
        mvc.perform(create(owner, RANGE)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeZone").value("UTC"))
                .andExpect(jsonPath("$.startAt").value("2026-11-13T01:00:00Z"))
                .andExpect(jsonPath("$.minutes").value(60));
        String id = idOn("2026-11-13");
        String changedZone = RANGE.replace("\"UTC\"", "\"Etc/GMT\"");
        mvc.perform(auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON).content(changedZone))
                .andExpect(status().isUnprocessableContent());
        String duration = DURATION.replace("\"minutes\":1", "\"minutes\":60");
        mvc.perform(auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON).content(duration))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startAt").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.timeZone").doesNotHaveJsonPath());
    }

    @Test
    void rejectsNapWritesAndPreservesExistingNapAsReadOnlyHistory() throws Exception {
        String nap = DURATION.replace("MAIN", "NAP");
        mvc.perform(create(owner, nap)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("sleepType"));
        String legacyId = UUID.randomUUID().toString();
        mongo.insert(new SleepSession(legacyId, owner, "NAP", "DURATION", 25, "2026-11-13", null,
                FRIDAY, null, null, null, null, null));
        mvc.perform(create(owner, DURATION)).andExpect(status().isCreated());
        mvc.perform(update(idOn("2026-11-13"), nap)).andExpect(status().isUnprocessableContent());
        mvc.perform(update(legacyId, DURATION)).andExpect(status().isConflict());
        mvc.perform(auth(delete(SESSIONS + "/" + legacyId), owner)).andExpect(status().isConflict());
        mvc.perform(auth(get(SESSIONS + "/" + legacyId), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sleepType").value("NAP")).andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void greatestStatisticalDayControlsMutationsEvenAfterBackfillAndDayChange() throws Exception {
        mvc.perform(create(owner, DURATION)).andExpect(status().isCreated());
        String latest = idOn("2026-11-13");
        mvc.perform(create(owner, durationOn("2026-11-12"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.editable").value(false));
        String older = idOn("2026-11-12");
        mvc.perform(update(older, durationOn("2026-11-11"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_RECORD_NOT_LATEST"));
        mvc.perform(auth(delete(SESSIONS + "/" + older), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_RECORD_NOT_LATEST"));
        mvc.perform(update(latest, durationOn("2026-11-12"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_DAY_ALREADY_RECORDED"));
        mvc.perform(update(latest, durationOn("2026-11-11"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value("2026-11-11")).andExpect(jsonPath("$.editable").value(false));
        mvc.perform(auth(get(SESSIONS + "/" + older), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void conditionalWritesRejectARecordThatChangedAfterItWasOpened() throws Exception {
        mvc.perform(create(owner, DURATION)).andExpect(status().isCreated());
        String id = idOn("2026-11-13");
        String original = "\"" + FRIDAY + "\"";
        mvc.perform(update(id, DURATION.replace("\"minutes\":1", "\"minutes\":90"))
                        .header("If-Match", original)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(90));
        mvc.perform(update(id, DURATION).header("If-Match", original)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_STALE"));
        mvc.perform(auth(delete(SESSIONS + "/" + id), owner).header("If-Match", original))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLEEP_STALE"));
        mvc.perform(auth(get(SESSIONS + "/" + id), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(90));
    }

    @Test
    void mondayMidnightInVietnamImmediatelyLocksSundayRecords() throws Exception {
        String sunday = "2026-11-15";
        clock.now = Instant.parse("2026-11-15T16:59:59Z");
        mvc.perform(create(owner, durationOn(sunday))).andExpect(status().isCreated());
        String id = idOn(sunday);
        clock.now = Instant.parse("2026-11-15T17:00:00Z");
        mvc.perform(create(stranger, durationOn(sunday))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_WEEK_LOCKED"));
        mvc.perform(update(id, durationOn("2026-11-16"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_WEEK_LOCKED"));
        mvc.perform(auth(delete(SESSIONS + "/" + id), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_WEEK_LOCKED"));
        mvc.perform(auth(get(SESSIONS + "/" + id), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(create(owner, durationOn("2026-11-16"))).andExpect(status().isCreated());
    }

    @Test
    void differentStatisticalDaysStillRejectOverlappingRangesButAllowAdjacentRanges() throws Exception {
        mvc.perform(create(owner, RANGE.replace("2026-11-13", "2026-11-12"))).andExpect(status().isCreated());
        String overlapping = rangeBetween("01");
        mvc.perform(create(owner, overlapping)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLEEP_OVERLAP"));
        mvc.perform(create(owner, rangeBetween("02"))).andExpect(status().isCreated());
    }

    @Test
    void concurrentCreatesAndDirectMongoWritesCannotDuplicateAnOwnerDay() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentCreate(DURATION, ready, start));
            var second = executor.submit(() -> concurrentCreate(RANGE, ready, start));
            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(bothReady);
            assertEquals(List.of(201, 409), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        }
        var owned = Query.query(Criteria.where("owner").is(owner).and("statistical_day").is("2026-11-13"));
        assertEquals(1, mongo.count(owned, SleepSession.class));
        assertThrows(DuplicateKeyException.class, () -> mongo.insert(new SleepSession(UUID.randomUUID().toString(),
                owner, "MAIN", "DURATION", 10, "2026-11-13", null, FRIDAY, null, null, null, null, null)));
        mvc.perform(create(stranger, DURATION)).andExpect(status().isCreated());
    }

    private int concurrentCreate(String body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent create did not start.");
        return mvc.perform(create(owner, body)).andReturn().getResponse().getStatus();
    }

    private String idOn(String day) {
        return mongo.findOne(Query.query(Criteria.where("owner").is(owner).and("statistical_day").is(day)
                .and("sleep_type").is("MAIN")), SleepSession.class).getId();
    }

    private static String rangeBetween(String startHour) {
        return RANGE.replace("\"start\":{\"day\":\"2026-11-13\",\"hour\":\"01\"",
                        "\"start\":{\"day\":\"2026-11-12\",\"hour\":\"" + startHour + "\"")
                .replace("\"hour\":\"02\",\"minute\":\"00\"}}", "\"hour\":\"00\",\"minute\":\"30\"}}");
    }

    private static String durationOn(String day) {
        return DURATION.replace("2026-11-13", day);
    }

    private MockHttpServletRequestBuilder update(String id, String body) {
        return auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder create(String account, String body) {
        return auth(post(SESSIONS), account).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String account) {
        return request.header("Authorization", "Bearer " + tokens.issueAccess(account, Instant.now()));
    }

    @TestConfiguration
    static class SleepClockConfig {
        @Bean
        @Primary
        SleepClock sleepTestClock() { return new SleepClock(); }
    }

    static final class SleepClock extends Clock {
        private volatile Instant now = FRIDAY;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
