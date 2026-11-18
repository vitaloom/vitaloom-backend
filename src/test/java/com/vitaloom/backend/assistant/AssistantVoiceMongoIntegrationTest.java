package com.vitaloom.backend.assistant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(properties = {
    "vitaloom.test.mongodb.database=assistant_voice_test",
    "ASSISTANT_GEMINI_API_KEY=fixture-only-fake-key",
    "ASSISTANT_VECTOR_MONGO_URI=",
    "ASSISTANT_VOICE_ENABLED=true",
    "ASSISTANT_VOICE_STT_MODEL=fixture-model"
})
@TestPropertySource("/mongo-integration-test.properties")
@AutoConfigureMockMvc
@Import(MongoTestcontainersConfiguration.class)
class AssistantVoiceMongoIntegrationTest {
    private static final String PATH = "/api/assistant/mvp/voice-turns";
    private static final byte[] WATER_AUDIO = {1, 2, 3};
    private static final byte[] SLEEP_AUDIO = {4, 5, 6};
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @MockitoBean AssistantSpeechToText stt;
    @MockitoBean GeminiAssistantProvider gemini;

    @Test
    void writesThroughAuthenticatedOwnerServicesAndReplaysTheWaterTurn() throws Exception {
        String owner = "voice-" + UUID.randomUUID();
        String today = LocalDate.now(AssistantVoiceActions.ZONE).toString();
        UUID waterTurn = UUID.randomUUID();
        mvc.perform(voice(waterTurn, WATER_AUDIO, true))
                .andExpect(status().isUnauthorized());
        mvc.perform(voice(waterTurn, WATER_AUDIO, false).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stt, gemini);

        String water = "Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi";
        when(stt.transcribe(WATER_AUDIO)).thenReturn(water);
        when(gemini.selectVoiceTool(water)).thenReturn(new ToolCall("w", "function", "recordWater",
                "{\"amount\":\"250ml\",\"when\":\"tại thời điểm hiện tại\"}"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(voice(waterTurn, WATER_AUDIO, true).header("Authorization", auth(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turns[0].text").value(water))
                    .andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED"));
        }
        verify(gemini).selectVoiceTool(water);
        mvc.perform(waterSummary(today, owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(250));
        mvc.perform(waterSummary(today, "other-" + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(0));

        String sleep = "Ghi 8 tiếng ngủ hôm nay, cảm giác tốt";
        when(stt.transcribe(SLEEP_AUDIO)).thenReturn(sleep);
        when(gemini.selectVoiceTool(sleep)).thenReturn(new ToolCall("s", "function", "recordSleep",
                "{\"duration\":\"8 tiếng\",\"day\":\"hôm nay\",\"feeling\":\"tốt\"}"));
        mvc.perform(voice(UUID.randomUUID(), SLEEP_AUDIO, true).header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED"));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].day").value(today))
                .andExpect(jsonPath("$.sessions[0].minutes").value(480))
                .andExpect(jsonPath("$.sessions[0].feeling").value("COMFORTABLE"));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth("other-" + owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isEmpty());
    }

    private MockMultipartHttpServletRequestBuilder voice(UUID turnId, byte[] audio, boolean consent) {
        return multipart(PATH).file(new MockMultipartFile("audio", "voice.m4a", "audio/mp4", audio))
                .param("turnId", turnId.toString()).param("consentAccepted", Boolean.toString(consent));
    }

    private MockHttpServletRequestBuilder waterSummary(String day, String owner) {
        return get("/api/water/daily-summary").param("date", day).param("timeZone", AssistantVoiceActions.ZONE.getId())
                .header("Authorization", auth(owner));
    }

    private String auth(String account) {
        return "Bearer " + tokens.issueAccess(account, Instant.now());
    }
}
