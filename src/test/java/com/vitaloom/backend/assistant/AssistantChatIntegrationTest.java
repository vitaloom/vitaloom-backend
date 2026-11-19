package com.vitaloom.backend.assistant;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "vitaloom.test.mongodb.database=assistant_chat_test",
    "spring.mongodb.database=assistant_chat_test",
    "ASSISTANT_GEMINI_API_KEY=fixture-only-fake-key",
    "ASSISTANT_VECTOR_MONGO_URI=",
    "vitaloom.assistant.sandbox.account-id=",
    "ASSISTANT_COURSE_ACCOUNT_IDS="
})
@TestPropertySource("/mongo-integration-test.properties")
@AutoConfigureMockMvc
@Import(MongoTestcontainersConfiguration.class)
class AssistantChatIntegrationTest {
    private static final String ACCOUNT = "assistant-synthetic";
    private static final String QUESTION = "Mình xem nhật ký giấc ngủ ở đâu?";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean AssistantProvider provider;

    @BeforeEach
    void enableTheAccountOptInForSyntheticFixtureScenarios() throws Exception {
        for (String account : new String[] {ACCOUNT, "other-account"}) {
            mvc.perform(put("/api/assistant/mvp/settings").header("Authorization", auth(account))
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"personalDataAllowed":true,"consentVersion":"gemini-health-v1"}
                            """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.personalDataAllowed").value(true));
        }
    }

    @Test
    void defaultProfileWithOnlyAKeyEnablesBothRoutesForAuthenticatedUsers() throws Exception {
        var request = request(UUID.randomUUID(), true, QUESTION);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(request(UUID.randomUUID(), true, QUESTION).header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assistant/mvp/conversations").header("Authorization", auth("other-account")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    void unsupportedTextNeverReachesTheProvider() throws Exception {
        mvc.perform(request(UUID.randomUUID(), true, "Tôi muốn hỏi chuyện khác.").header("Authorization", auth(ACCOUNT)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("INSUFFICIENT_EVIDENCE"))
            .andExpect(jsonPath("$.citations").isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    void legacyFixtureAcceptsOnlyTheAccountPermissionWithoutAPerTurnFlag() throws Exception {
        when(provider.confirmsFixture()).thenReturn(true);
        mvc.perform(post("/api/assistant/chat").header("Authorization", auth(ACCOUNT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"turnId":"%s","text":"%s"}
                                """.formatted(UUID.randomUUID(), QUESTION)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ANSWERED"));
        verify(provider).confirmsFixture();
    }

    @Test
    void legacyFixtureEndpointRejectsAccountsWithTheToggleOff() throws Exception {
        mvc.perform(put("/api/assistant/mvp/settings").header("Authorization", auth(ACCOUNT))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"personalDataAllowed":false}
                        """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.personalDataAllowed").value(false));

        mvc.perform(request(UUID.randomUUID(), true, QUESTION).header("Authorization", auth(ACCOUNT)))
            .andExpect(status().isForbidden());

        verifyNoInteractions(provider);
    }

    @Test
    void rejectsUnexpectedFieldsAndCoercedConsent() throws Exception {
        mvc.perform(post("/api/assistant/chat").header("Authorization", auth(ACCOUNT))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"turnId":"%s","text":"%s","consentAccepted":true,"accountId":"another-user"}
                """.formatted(UUID.randomUUID(), QUESTION)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/assistant/chat").header("Authorization", auth(ACCOUNT))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"turnId":"%s","text":"%s","consentAccepted":"true"}
                """.formatted(UUID.randomUUID(), QUESTION)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(provider);
    }

    @Test
    void answersOnceAndReplaysItsServerOwnedCitation() throws Exception {
        when(provider.confirmsFixture()).thenReturn(true);
        UUID id = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(request(id, true, QUESTION).header("Authorization", auth(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turnId").value(id.toString()))
                .andExpect(jsonPath("$.outcome").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Mở mục Giấc ngủ rồi chọn Nhật ký."))
                .andExpect(jsonPath("$.citations[0].id").value("app-help-sleep-journal"))
                .andExpect(jsonPath("$.citations[0].version").value(1));
        }
        verify(provider).confirmsFixture();
    }

    @Test
    void replaysProviderFailureWithoutRedispatch() throws Exception {
        when(provider.confirmsFixture()).thenThrow(new IllegalStateException("test-only transport detail"));
        UUID id = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(request(id, true, QUESTION).header("Authorization", auth(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.citations").isEmpty());
        }
        verify(provider).confirmsFixture();
    }

    @Test
    void inFlightReplayDoesNotSendAnotherRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.confirmsFixture()).thenAnswer(call -> {
            entered.countDown();
            return release.await(10, TimeUnit.SECONDS);
        });
        UUID id = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(request(id, true, QUESTION).header("Authorization", auth(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ANSWERED")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                mvc.perform(request(id, true, QUESTION).header("Authorization", auth(ACCOUNT)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_TURN_PENDING"));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(provider).confirmsFixture();
    }

    private String auth(String account) {
        return "Bearer " + tokens.issueAccess(account, Instant.now());
    }

    private MockHttpServletRequestBuilder request(UUID id, boolean consent, String question) {
        return post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content("""
            {"turnId":"%s","text":"%s","consentAccepted":%s}
            """.formatted(id, question, consent));
    }
}
