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
import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.model.SleepSession.CivilTime;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = "vitaloom.test.mongodb.database=sleep_recent_test")
@AutoConfigureMockMvc
@Import({SleepRecentReportMongoIntegrationTest.ReportClockConfig.class, MongoTestcontainersConfiguration.class})
class SleepRecentReportMongoIntegrationTest {
    private static final String SESSIONS = "/api/sleep/v2/sessions";
    private static final String REPORT = "/api/sleep/recent";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired MongoTemplate mongo;
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
                .andExpect(jsonPath("$.insights[0].denominatorDays").value(7));

        mvc.perform(auth(get(REPORT), other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NO_DATA"))
                .andExpect(jsonPath("$.recordedDays").value(0))
                .andExpect(jsonPath("$.averageMinutesPerRecordedDay").value(nullValue()))
                .andExpect(jsonPath("$.insights.length()").value(0));
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
    void expiredJwtCannotReadRecentReport() throws Exception {
        String expired = tokens.issueAccess(owner, Instant.parse("2000-01-01T00:00:00Z"));
        mvc.perform(get(REPORT).header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
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
