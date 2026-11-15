package com.vitaloom.backend.sleep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "/mongo-integration-test.properties",
        properties = "vitaloom.test.mongodb.database=sleep_recent_error_test")
@AutoConfigureMockMvc
@Import(MongoTestcontainersConfiguration.class)
class SleepRecentReportErrorTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean SleepMainReadQuery mainSleep;

    @Test
    void mongoReadFailureIsProblemDetailInternalError() throws Exception {
        when(mainSleep.readWindow(any(), any(), any())).thenThrow(new IllegalStateException("test failure"));

        mvc.perform(get("/api/sleep/recent")
                        .header("Authorization", "Bearer " + tokens.issueAccess("account-a", Instant.now())))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
