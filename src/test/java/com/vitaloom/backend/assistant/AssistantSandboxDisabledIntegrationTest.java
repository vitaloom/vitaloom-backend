package com.vitaloom.backend.assistant;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "vitaloom.test.mongodb.database=assistant_disabled_test",
    "spring.mongodb.database=assistant_disabled_test",
    "ASSISTANT_GEMINI_API_KEY= "
})
@TestPropertySource("/mongo-integration-test.properties")
@AutoConfigureMockMvc
@Import(MongoTestcontainersConfiguration.class)
class AssistantSandboxDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired MongoTemplate mongo;

    @Test
    void flagAloneCannotEnableAssistantOrCreateAClaimCollection() throws Exception {
        mvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer " + tokens.issueAccess("assistant-synthetic", Instant.now())))
            .andExpect(status().isNotFound());
        assertThat(mongo.collectionExists("assistant_sandbox_turns")).isFalse();
    }
}
