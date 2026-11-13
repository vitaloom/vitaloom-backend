package com.vitaloom.backend.sleep;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vitaloom.backend.auth.service.JwtTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SleepJournalMongoIntegrationTest {
    private static final String SESSIONS = "/api/sleep/v2/sessions";
    private static final String DURATION = """
            {"sleepType":"NAP","method":"DURATION","endDay":"2020-01-02","minutes":1}
            """;
    private static final String RANGE = """
            {"sleepType":"MAIN","method":"RANGE","timeZone":"UTC",
             "start":{"day":"2020-01-02","hour":"01","minute":"00"},
             "end":{"day":"2020-01-02","hour":"02","minute":"00"}}
            """;

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired JwtTokenService tokens;
    @LocalServerPort int port;
    private final String owner = UUID.randomUUID().toString();
    private final String stranger = UUID.randomUUID().toString();

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
                .andExpect(jsonPath("$.id").isNotEmpty()).andExpect(jsonPath("$.sleepType").value("NAP"))
                .andExpect(jsonPath("$.method").value("DURATION")).andExpect(jsonPath("$.minutes").value(1))
                .andExpect(jsonPath("$.day").value("2020-01-02"))
                .andExpect(jsonPath("$.startAt").doesNotHaveJsonPath());
        String id = mongo.find(Query.query(Criteria.where("owner").is(owner)),
                com.vitaloom.backend.sleep.model.SleepSession.class).getFirst().getId();

        mvc.perform(create(owner, DURATION.replace("\"NAP\"", "\"MAIN\"").replace("\"minutes\":1", "\"minutes\":1440")))
                .andExpect(status().isCreated());
        mvc.perform(auth(get(SESSIONS), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2));
        mvc.perform(auth(get(SESSIONS).param("from", "2020-01-02").param("to", "2020-01-02"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions.length()").value(2));
        mvc.perform(auth(get(SESSIONS).param("from", "2020-01-03"), owner)).andExpect(status().isOk())
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

        mvc.perform(auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON)
                        .content(DURATION.replace("\"NAP\"", "\"MAIN\"").replace("\"minutes\":1", "\"minutes\":90")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sleepType").value("MAIN"))
                .andExpect(jsonPath("$.minutes").value(90));
        mvc.perform(auth(delete(SESSIONS + "/" + id), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(get(SESSIONS + "/" + id), owner)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SLEEP_NOT_FOUND"));
    }

    @Test
    void invalidDurationAndWrongModeFieldsAreRejected() throws Exception {
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":0")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SLEEP_VALIDATION"));
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":1.5")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":1441")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, DURATION.replace("\"minutes\":1", "\"minutes\":1").replace("\"method\":\"DURATION\"", "\"method\":\"DURATION\",\"timeZone\":\"UTC\"")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(create(owner, DURATION.replace("2020-01-02", "2999-01-02")))
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
            org.junit.jupiter.api.Assertions.assertEquals(201, created.statusCode());
            String id = mongo.find(Query.query(Criteria.where("owner").is(owner)),
                    com.vitaloom.backend.sleep.model.SleepSession.class).getFirst().getId();

            HttpRequest detail = HttpRequest.newBuilder(URI.create(sessionsUrl + "/" + id))
                    .header("Authorization", authorization).GET().build();
            HttpResponse<String> response = client.send(detail, HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode());
            org.junit.jupiter.api.Assertions.assertTrue(response.body().contains("\"minutes\":1"));
        }
    }

    @Test
    void rangeRecordsKeepReferenceTimezoneAndCanConvertToDuration() throws Exception {
        mvc.perform(create(owner, RANGE)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeZone").value("UTC"))
                .andExpect(jsonPath("$.startAt").value("2020-01-02T01:00:00Z"))
                .andExpect(jsonPath("$.minutes").value(60));
        String id = mongo.find(Query.query(Criteria.where("owner").is(owner)),
                com.vitaloom.backend.sleep.model.SleepSession.class).getFirst().getId();
        String changedZone = RANGE.replace("\"UTC\"", "\"Etc/GMT\"");
        mvc.perform(auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON).content(changedZone))
                .andExpect(status().isUnprocessableContent());
        String duration = DURATION.replace("\"NAP\"", "\"MAIN\"").replace("\"minutes\":1", "\"minutes\":60");
        mvc.perform(auth(put(SESSIONS + "/" + id), owner).contentType(MediaType.APPLICATION_JSON).content(duration))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startAt").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.timeZone").doesNotHaveJsonPath());
    }

    private MockHttpServletRequestBuilder create(String account, String body) {
        return auth(post(SESSIONS), account).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String account) {
        return request.header("Authorization", "Bearer " + tokens.issueAccess(account, Instant.now()));
    }
}
