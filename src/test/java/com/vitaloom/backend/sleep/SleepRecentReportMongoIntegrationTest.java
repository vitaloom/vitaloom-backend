package com.vitaloom.backend.sleep;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.sleep.controller.SleepReportInsightController;
import com.vitaloom.backend.sleep.goal.SleepGoalRepository;
import com.vitaloom.backend.sleep.goal.SleepGoalService;
import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.model.SleepSession.CivilTime;
import com.vitaloom.backend.sleep.goal.SleepGoalHistory;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = {"vitaloom.test.mongodb.database=sleep_recent_test", "vitaloom.sleep.story.enabled=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({SleepRecentReportMongoIntegrationTest.ReportClockConfig.class, MongoTestcontainersConfiguration.class})
class SleepRecentReportMongoIntegrationTest {
    private static final String SESSIONS = "/api/sleep/v2/sessions";
    private static final String REPORT = "/api/sleep/recent";
    private static final String INSIGHTS = "/api/sleep/insights";
    private static final String CONSENT = "/api/sleep/ai/consent";
    private static final String STORY = "/api/sleep/recent/story";
    private static final String GOAL = "/api/sleep/goal";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired MongoTemplate mongo;
    @Autowired SleepGoalRepository goalRepository;
    private final String owner = "sleep-recent-" + UUID.randomUUID();
    private final String other = "sleep-recent-" + UUID.randomUUID();

    @Test
    void confirmedJournalWritesFlowThroughMongoIntoAuthenticatedRecentReport() throws Exception {
        createDuration("2026-11-09", 420);
        createDuration("2026-11-11", 450);
        createDuration("2026-11-14", 451);

        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(handler().handlerType(SleepReportInsightController.class))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.period.start").value("2026-11-08"))
                .andExpect(jsonPath("$.period.end").value("2026-11-14"))
                .andExpect(jsonPath("$.period.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.dataStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].day").value("2026-11-08"))
                .andExpect(jsonPath("$.days[0].minutes").value(nullValue()))
                .andExpect(jsonPath("$.days[1].minutes").value(420))
                .andExpect(jsonPath("$.recordedDays").value(3))
                .andExpect(jsonPath("$.coverageDenominator").value(7))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(440))
                .andExpect(jsonPath("$.insights[0].code").value("A01"))
                .andExpect(jsonPath("$.insights[0].status").value("READY"))
                .andExpect(jsonPath("$.insights[0].sampleDays").value(3))
                .andExpect(jsonPath("$.insights[0].denominatorDays").value(7))
                .andExpect(jsonPath("$.highlights.length()").value(1))
                .andExpect(jsonPath("$.highlights[0].code").value("A01"))
                .andExpect(jsonPath("$.highlights[0].status").value("READY"))
                .andExpect(jsonPath("$.highlights[0].title").value("Recording coverage"));

        mvc.perform(auth(get(REPORT), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NO_DATA"))
                .andExpect(jsonPath("$.recordedDays").value(0))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(nullValue()))
                .andExpect(jsonPath("$.insights.length()").value(0))
                .andExpect(jsonPath("$.highlights.length()").value(0));
    }

    @Test
    void routeRequiresJwtAndRejectsAccountSelectionAndDateQuery() throws Exception {
        mvc.perform(auth(get("/api/statistics/sleep/recent"), owner)).andExpect(status().isNotFound());
        mvc.perform(get(REPORT)).andExpect(status().isUnauthorized());
        mvc.perform(get(REPORT).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth(get(REPORT).queryParam("accountId", other), owner))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(auth(get(REPORT).queryParam("week", "2026-11-09"), owner))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void sleepGoalHistoryIsAuthenticatedVersionedAndEffectiveByDay() throws Exception {
        mvc.perform(get(GOAL)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(GOAL), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.minutes").value(nullValue()))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.effectiveDay").value(nullValue()))
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":480,\"revision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minutes").value(480))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.effectiveDay").value("2026-11-14"));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-13"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(nullValue()));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-14"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(480));
        mvc.perform(auth(get(GOAL), other)).andExpect(status().isOk()).andExpect(jsonPath("$.minutes").value(nullValue()));
        mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":480,\"revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLEEP_GOAL_CONFLICT"));
        mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":61,\"revision\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":480.5,\"revision\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-14", "2026-11-13"), owner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(GOAL).queryParam("day", "+10000-01-01"), owner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(delete(GOAL).queryParam("revision", "1", "2"), owner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(delete(GOAL).queryParam("revision", "1"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(nullValue())).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-13"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(nullValue()));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-14"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(nullValue())).andExpect(jsonPath("$.effectiveDay").value("2026-11-14"));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-15"), owner)).andExpect(status().isBadRequest());
    }

    @Test
    void sleepGoalRemovalPreservesThePreviousDayAcrossVietnamMidnight() throws Exception {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-11-13T16:59:59Z"), ZoneOffset.UTC);
        new SleepGoalService(goalRepository, beforeMidnight, zone).set(owner, 60, 0L);

        mvc.perform(auth(get(GOAL), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(60))
                .andExpect(jsonPath("$.effectiveDay").value("2026-11-13"));
        mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":1440,\"revision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minutes").value(1440))
                .andExpect(jsonPath("$.effectiveDay").value("2026-11-14"));
        for (int invalid : new int[] {45, 1455}) {
            mvc.perform(auth(put(GOAL), owner).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":" + invalid + ",\"revision\":2}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(auth(delete(GOAL).queryParam("revision", "2"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.minutes").value(nullValue()))
                .andExpect(jsonPath("$.effectiveDay").value("2026-11-14"));
        mvc.perform(auth(get(GOAL).queryParam("day", "2026-11-13"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minutes").value(60))
                .andExpect(jsonPath("$.effectiveDay").value("2026-11-13"));
        mvc.perform(auth(get(GOAL), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(nullValue()))
                .andExpect(jsonPath("$.effectiveDay").value("2026-11-14"));
    }

    @Test
    void goalCatalogEvaluatesA02AndB04AgainstHistoricalGoalsAndKeepsDefaultResponse() throws Exception {
        createDurationWithFeeling("2026-11-11", 450, "TIRED");
        createDurationWithFeeling("2026-11-12", 480, "TIRED");
        createDurationWithFeeling("2026-11-13", 450, "NORMAL");
        createDurationWithFeeling("2026-11-14", 600, "TIRED");
        mongo.insert(new SleepGoalHistory(owner, 4, java.util.List.of(
                new SleepGoalHistory.Snapshot("2026-11-11", 450, false),
                new SleepGoalHistory.Snapshot("2026-11-12", 480, false),
                new SleepGoalHistory.Snapshot("2026-11-13", 510, false),
                new SleepGoalHistory.Snapshot("2026-11-14", null, true))));

        mvc.perform(auth(get(INSIGHTS), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].code").value("A01"))
                .andExpect(jsonPath("$.items[1].code").value("B01"));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].code").value("A01"))
                .andExpect(jsonPath("$.items[1].code").value("A02"))
                .andExpect(jsonPath("$.items[1].status").value("READY"))
                .andExpect(jsonPath("$.items[1].sample.eligibleMainDays").value(3))
                .andExpect(jsonPath("$.items[1].evidence.metGoalDays").value(2))
                .andExpect(jsonPath("$.items[2].code").value("B01"))
                .andExpect(jsonPath("$.items[3].code").value("B04"))
                .andExpect(jsonPath("$.items[3].status").value("READY"))
                .andExpect(jsonPath("$.items[3].sample.ratedMain").value(4))
                .andExpect(jsonPath("$.items[3].sample.comparableRatedMain").value(3))
                .andExpect(jsonPath("$.items[3].evidence.exceptionCount").value(2))
                .andExpect(jsonPath("$.items[3].evidence.ratedMain").value(4))
                .andExpect(jsonPath("$.items[3].evidence.exceptions[0].goalMinutes").value(450))
                .andExpect(jsonPath("$.items[3].evidence.exceptions[1].goalMinutes").value(480));
        mvc.perform(auth(get(INSIGHTS + "/A02"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sample.eligibleMainDays").value(3))
                .andExpect(jsonPath("$.evidence.metGoalDays").value(2));
        mvc.perform(auth(get(INSIGHTS + "/B04"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.exceptionCount").value(2))
                .andExpect(jsonPath("$.evidence.exceptions.length()").value(2));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[1].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[2].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[3].status").value("NO_DATA"));
        mvc.perform(auth(get(INSIGHTS + "/A02"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA"));
        mvc.perform(auth(get(INSIGHTS + "/B04"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA"));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "unknown"), owner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal", "goal"), owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readyDetailsPublishSevenDayEvidenceAndFingerprintChangesWithFacts() throws Exception {
        createDuration("2026-11-09", 400);
        createDuration("2026-11-10", 500);
        createDuration("2026-11-11", 500);
        createDuration("2026-11-12", 400);
        createDuration("2026-11-14", 400);
        mongo.insert(new SleepGoalHistory(owner, 1, java.util.List.of(
                new SleepGoalHistory.Snapshot("2026-11-10", 600, false),
                new SleepGoalHistory.Snapshot("2026-11-11", 450, false),
                new SleepGoalHistory.Snapshot("2026-11-12", null, true),
                new SleepGoalHistory.Snapshot("2026-11-14", 390, false))));

        String catalog = mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String listA01Version = JsonPath.read(catalog, "$.items[0].evidenceVersion");
        String listA02Version = JsonPath.read(catalog, "$.items[1].evidenceVersion");
        var a01 = mvc.perform(auth(get(INSIGHTS + "/A01"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.days.length()").value(7))
                .andExpect(jsonPath("$.evidence.days[0].day").value("2026-11-08"))
                .andExpect(jsonPath("$.evidence.days[0].status").value("NOT_RECORDED"))
                .andExpect(jsonPath("$.evidence.days[4].day").value("2026-11-12"))
                .andExpect(jsonPath("$.evidence.days[4].status").value("RECORDED"))
                .andExpect(jsonPath("$.sample.recordedMainDays").value(5))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(listA01Version,
                JsonPath.read(a01.getResponse().getContentAsString(), "$.evidenceVersion"));

        var a02 = mvc.perform(auth(get(INSIGHTS + "/A02"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.evidence.metGoalDays").value(2))
                .andExpect(jsonPath("$.evidence.eligibleMainDays").value(3))
                .andExpect(jsonPath("$.evidence.days.length()").value(7))
                .andExpect(jsonPath("$.evidence.days[1].status").value("NO_GOAL"))
                .andExpect(jsonPath("$.evidence.days[2].status").value("MISSED"))
                .andExpect(jsonPath("$.evidence.days[4].status").value("NO_GOAL"))
                .andExpect(jsonPath("$.evidence.days[5].status").value("NO_MAIN"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(listA02Version,
                JsonPath.read(a02.getResponse().getContentAsString(), "$.evidenceVersion"));
        String a02Version = JsonPath.read(a02.getResponse().getContentAsString(), "$.evidenceVersion");

        // Moving Goal coverage from one MAIN day to another preserves counts but changes dated classes.
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(owner)), SleepGoalHistory.class);
        mongo.insert(new SleepGoalHistory(owner, 2, java.util.List.of(
                new SleepGoalHistory.Snapshot("2026-11-09", 390, false),
                new SleepGoalHistory.Snapshot("2026-11-10", 600, false),
                new SleepGoalHistory.Snapshot("2026-11-11", 450, false),
                new SleepGoalHistory.Snapshot("2026-11-12", null, true),
                new SleepGoalHistory.Snapshot("2026-11-14", null, true))));
        var updatedA02 = mvc.perform(auth(get(INSIGHTS + "/A02"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.metGoalDays").value(2))
                .andExpect(jsonPath("$.evidence.eligibleMainDays").value(3))
                .andExpect(jsonPath("$.evidence.days[1].status").value("MET"))
                .andExpect(jsonPath("$.evidence.days[6].status").value("NO_GOAL"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertNotEquals(a02Version,
                JsonPath.read(updatedA02.getResponse().getContentAsString(), "$.evidenceVersion"));
    }

    @Test
    void goalCatalogDistinguishesMissingGoalsFromUnverifiedHistory() throws Exception {
        createDurationWithFeeling("2026-11-12", 420, "TIRED");
        createDurationWithFeeling("2026-11-13", 435, "NORMAL");
        createDurationWithFeeling("2026-11-14", 450, "NORMAL");
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].code").value("A02"))
                .andExpect(jsonPath("$.items[1].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[3].code").value("B04"))
                .andExpect(jsonPath("$.items[3].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.items[2].status").value("READY"));

        mongo.getCollection("sleep_goal_history").insertOne(new Document("_id", owner)
                .append("revision", 1)
                .append("snapshots", java.util.List.of(new Document("effective_day", "2026-11-12")
                        .append("minutes", 61).append("removed", false))));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[1].status").value("UNVERIFIED_DATA"))
                .andExpect(jsonPath("$.items[2].status").value("READY"))
                .andExpect(jsonPath("$.items[3].status").value("UNVERIFIED_DATA"));
        mvc.perform(auth(get(INSIGHTS + "/A02"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED_DATA"));
        mvc.perform(auth(get(INSIGHTS + "/B04"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED_DATA"));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[3].status").value("NO_DATA"));
        mongo.getCollection("sleep_goal_history").insertOne(new Document("_id", other)
                .append("revision", 1)
                .append("snapshots", java.util.List.of(new Document("effective_day", "2026-11-12")
                        .append("minutes", 61).append("removed", false))));
        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[1].status").value("UNVERIFIED_DATA"))
                .andExpect(jsonPath("$.items[2].status").value("NO_DATA"))
                .andExpect(jsonPath("$.items[3].status").value("UNVERIFIED_DATA"));
    }

    @Test
    void goalCatalogKeepsA02ReadyWhenB04HasTwoRatedMainDays() throws Exception {
        createDurationWithFeeling("2026-11-12", 450, "TIRED");
        createDurationWithFeeling("2026-11-13", 480, "NORMAL");
        createDuration("2026-11-14", 510);
        mongo.insert(new SleepGoalHistory(owner, 1, java.util.List.of(
                new SleepGoalHistory.Snapshot("2026-11-12", 450, false))));

        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("A01"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[1].code").value("A02"))
                .andExpect(jsonPath("$.items[1].status").value("READY"))
                .andExpect(jsonPath("$.items[1].sample.eligibleMainDays").value(3))
                .andExpect(jsonPath("$.items[2].code").value("B01"))
                .andExpect(jsonPath("$.items[2].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.items[3].code").value("B04"))
                .andExpect(jsonPath("$.items[3].status").value("INSUFFICIENT_DATA"));
    }

    @Test
    void goalCatalogKeepsB04ReadyAtThreeRatedDaysWhenA02HasTwoGoalDays() throws Exception {
        createDurationWithFeeling("2026-11-12", 450, "TIRED");
        createDurationWithFeeling("2026-11-13", 480, "NORMAL");
        createDurationWithFeeling("2026-11-14", 510, "TIRED");
        mongo.insert(new SleepGoalHistory(owner, 1, java.util.List.of(
                new SleepGoalHistory.Snapshot("2026-11-12", 450, false),
                new SleepGoalHistory.Snapshot("2026-11-14", null, true))));

        mvc.perform(auth(get(INSIGHTS).queryParam("catalog", "goal"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("A01"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[1].code").value("A02"))
                .andExpect(jsonPath("$.items[1].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.items[2].code").value("B01"))
                .andExpect(jsonPath("$.items[2].status").value("READY"))
                .andExpect(jsonPath("$.items[3].code").value("B04"))
                .andExpect(jsonPath("$.items[3].status").value("READY"))
                .andExpect(jsonPath("$.items[3].sample.ratedMain").value(3))
                .andExpect(jsonPath("$.items[3].sample.comparableRatedMain").value(2))
                .andExpect(jsonPath("$.items[3].evidence.exceptionCount").value(1));
    }

    @Test
    void expiredJwtCannotReadRecentReport() throws Exception {
        String expired = tokens.issueAccess(owner, Instant.parse("2000-01-01T00:00:00Z"));
        mvc.perform(get(REPORT).header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void insightListAndDetailUseVerifiedRatedMainEvidenceAndAreFreshAndIsolated() throws Exception {
        createDurationWithFeeling("2026-11-09", 420, "COMFORTABLE");
        createDurationWithFeeling("2026-11-10", 430, null);
        createDurationWithFeeling("2026-11-11", 450, "NORMAL");
        String last = createDurationWithFeeling("2026-11-14", 451, "TIRED");
        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights[0].code").value("B01"))
                .andExpect(jsonPath("$.highlights[1].code").value("A01"));
        mvc.perform(auth(get(INSIGHTS), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].code").value("A01"))
                .andExpect(jsonPath("$.items[1].code").value("B01"))
                .andExpect(jsonPath("$.items[1].status").value("READY"));
        mvc.perform(auth(get(INSIGHTS + "/B01"), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.sample.ratedMain").value(3))
                .andExpect(jsonPath("$.sample.mainWithoutFeeling").value(1))
                .andExpect(jsonPath("$.evidence.comfortable.count").value(1))
                .andExpect(jsonPath("$.sourceRefs[0]").exists())
                .andExpect(jsonPath("$.limitations.length()", org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.policyVersion").exists())
                .andExpect(jsonPath("$.evidenceVersion").exists());
        mvc.perform(auth(put(SESSIONS + "/" + last), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sleepType\":\"MAIN\",\"method\":\"DURATION\",\"endDay\":\"2026-11-14\",\"minutes\":451,\"feeling\":\"COMFORTABLE\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(get(INSIGHTS + "/B01"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.comfortable.count").value(2));
        mvc.perform(auth(get(INSIGHTS), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("NO_DATA"));
        mvc.perform(get(INSIGHTS)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(INSIGHTS).queryParam("accountId", other), owner)).andExpect(status().isBadRequest());
        mvc.perform(auth(get(INSIGHTS + "/B01").queryParam("start", "2026-11-08"), owner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(INSIGHTS + "/A05"), owner)).andExpect(status().isNotFound());
    }

    @Test
    void recentHighlightsPreferCoverageWhenLatestMainHasNoFeeling() throws Exception {
        createDurationWithFeeling("2026-11-09", 420, "COMFORTABLE");
        createDurationWithFeeling("2026-11-10", 430, "NORMAL");
        createDurationWithFeeling("2026-11-11", 450, "TIRED");
        createDurationWithFeeling("2026-11-14", 451, null);

        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.insights[0].code").value("A01"))
                .andExpect(jsonPath("$.highlights[0].code").value("A01"))
                .andExpect(jsonPath("$.highlights[1].code").value("B01"));
    }

    @Test
    void insightStatesSeparateNoDataInsufficientAndUnverified() throws Exception {
        mvc.perform(auth(get(INSIGHTS + "/B01"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA"))
                .andExpect(jsonPath("$.sourceRefs").isArray());
        createDurationWithFeeling("2026-11-14", 420, null);
        mvc.perform(auth(get(INSIGHTS), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.items[1].locked").value(true))
                .andExpect(jsonPath("$.items[1].sample").doesNotExist());
        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights.length()").value(0));
        insertDuration(owner, "MAIN", "2026-11-12", 0);
        mvc.perform(auth(get(INSIGHTS + "/B01"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED_DATA"))
                .andExpect(jsonPath("$.evidence").doesNotExist());
    }

    @Test
    void storyRequiresPersistedAccountConsentAndCurrentReadyNonA01Evidence() throws Exception {
        createDurationWithFeeling("2026-11-09", 420, "COMFORTABLE");
        createDurationWithFeeling("2026-11-10", 430, "NORMAL");
        createDurationWithFeeling("2026-11-11", 450, "TIRED");

        mvc.perform(get(CONSENT)).andExpect(status().isUnauthorized());
        mvc.perform(get(STORY)).andExpect(status().isUnauthorized());

        mvc.perform(auth(get(CONSENT), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(auth(get(STORY), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.headline").doesNotExist())
                .andExpect(jsonPath("$.observations").doesNotExist());

        mvc.perform(auth(put(CONSENT), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(auth(get(CONSENT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(auth(get(CONSENT), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        var detail = mvc.perform(auth(get(INSIGHTS + "/B01"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        String fingerprint = JsonPath.read(detail.getResponse().getContentAsString(), "$.evidenceVersion");
        mvc.perform(auth(get(STORY), owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("STORY"))
                .andExpect(jsonPath("$.headline").isNotEmpty())
                .andExpect(jsonPath("$.observations.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(2)))
                .andExpect(jsonPath("$.observations[0].insightCode").value("B01"))
                .andExpect(jsonPath("$.observations[0].evidenceFingerprint").value(fingerprint));

        mvc.perform(auth(put(CONSENT), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(auth(get(STORY), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.headline").doesNotExist());
    }

    @Test
    void storyIsIneligibleWhenOnlyA01IsReady() throws Exception {
        createDuration("2026-11-09", 420);
        createDuration("2026-11-10", 430);
        createDuration("2026-11-11", 450);
        mvc.perform(auth(put(CONSENT), owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(auth(get(INSIGHTS), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[1].status").value("INSUFFICIENT_DATA"));
        mvc.perform(auth(get(STORY), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INELIGIBLE"))
                .andExpect(jsonPath("$.headline").doesNotExist())
                .andExpect(jsonPath("$.observations").doesNotExist());
    }

    private String createDurationWithFeeling(String day, int minutes, String feeling) throws Exception {
        var result = mvc.perform(auth(post(SESSIONS), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sleepType\":\"MAIN\",\"method\":\"DURATION\",\"endDay\":\""
                        + day + "\",\"minutes\":" + minutes
                        + (feeling == null ? "" : ",\"feeling\":\"" + feeling + "\"") + "}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void confirmedEditAndDeleteAreReflectedByFreshReportRead() throws Exception {
        createDuration("2026-11-09", 420);
        createDuration("2026-11-11", 450);
        String latestId = createDuration("2026-11-14", 451);

        mvc.perform(auth(put(SESSIONS + "/" + latestId), owner)
                        .contentType(MediaType.APPLICATION_JSON).content(durationBody("2026-11-14", 500)))
                .andExpect(status().isOk());
        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedDays").value(3))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(457))
                .andExpect(jsonPath("$.insights[0].code").value("A01"));

        mvc.perform(auth(delete(SESSIONS + "/" + latestId), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.recordedDays").value(2))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(435))
                .andExpect(jsonPath("$.insights.length()").value(0));
    }

    @Test
    void usesStoredWakeDayAndOnlyIncludesTheOwnersMainRecordsInTheWindow() throws Exception {
        mongo.insert(new SleepSession(UUID.randomUUID().toString(), owner, "MAIN", "RANGE", 420,
                "2026-11-08", null, Instant.parse("2026-11-09T08:00:00Z"), "America/Los_Angeles",
                new CivilTime("2026-11-08", "16", "30", -480.0),
                new CivilTime("2026-11-08", "23", "30", -480.0),
                Instant.parse("2026-11-09T00:30:00Z"), Instant.parse("2026-11-09T07:30:00Z")));
        insertDuration(owner, "MAIN", "2026-11-07", 120);
        insertDuration(owner, "MAIN", "2026-11-14", 451);
        insertDuration(owner, "MAIN", "2026-11-15", 120);
        insertDuration(owner, "NAP", "2026-11-10", 30);
        insertDuration(other, "MAIN", "2026-11-11", 600);

        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].day").value("2026-11-08"))
                .andExpect(jsonPath("$.days[0].minutes").value(420))
                .andExpect(jsonPath("$.days[1].status").value("NOT_RECORDED"))
                .andExpect(jsonPath("$.days[6].day").value("2026-11-14"))
                .andExpect(jsonPath("$.days[6].minutes").value(451))
                .andExpect(jsonPath("$.recordedDays").value(2))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(436))
                .andExpect(jsonPath("$.insights").isEmpty());

        insertDuration(owner, "MAIN", "2026-11-12", 0);
        mvc.perform(auth(get(REPORT), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("UNVERIFIED_DATA"))
                .andExpect(jsonPath("$.days[0].minutes").value(420))
                .andExpect(jsonPath("$.days[4].status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.days[4].minutes").value(nullValue()))
                .andExpect(jsonPath("$.recordedDays").value(nullValue()))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(nullValue()))
                .andExpect(jsonPath("$.insights").isEmpty());
    }

    private void insertDuration(String account, String type, String day, int minutes) {
        mongo.insert(new SleepSession(UUID.randomUUID().toString(), account, type, "DURATION", minutes,
                day, null, Instant.parse("2026-11-14T00:00:00Z"), null, null, null, null, null));
    }

    private String createDuration(String day, int minutes) throws Exception {
        var result = mvc.perform(auth(post(SESSIONS), owner).contentType(MediaType.APPLICATION_JSON)
                .content(durationBody(day, minutes))).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static String durationBody(String day, int minutes) {
        return "{\"sleepType\":\"MAIN\",\"method\":\"DURATION\",\"endDay\":\""
                + day + "\",\"minutes\":" + minutes + "}";
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String account) {
        return request.header("Authorization", "Bearer " + tokens.issueAccess(account, Instant.now()));
    }

    @TestConfiguration
    static class ReportClockConfig {
        @Bean
        @Primary
        Clock reportTestClock() { return Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC); }
    }
}
