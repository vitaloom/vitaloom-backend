package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssistantVoiceFlowTest {
    private static final String PATH = "/api/assistant/mvp/voice-turns";
    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final String OWNER = "synthetic-account";
    private static final byte[] AUDIO = {1, 2, 3};
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantSpeechToText stt = mock(AssistantSpeechToText.class);
    private final AssistantVoiceTools.Selector selector = mock(AssistantVoiceTools.Selector.class);
    private final WaterService water = mock(WaterService.class);
    private final SleepJournalService sleep = mock(SleepJournalService.class);
    private final Map<String, AssistantVoiceTurns.Claim> claims = new HashMap<>();
    private final Map<String, AssistantConversation> conversations = new HashMap<>();
    private final UUID turnId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void createFlow() {
        var indexes = mock(IndexOperations.class);
        when(mongo.indexOps(any(Class.class))).thenReturn(indexes);
        when(mongo.insert(any(AssistantVoiceTurns.Claim.class))).thenAnswer(invocation -> {
            AssistantVoiceTurns.Claim claim = invocation.getArgument(0);
            if (claims.putIfAbsent(claim.id(), claim) != null) throw new DuplicateKeyException("Duplicate claim");
            return claim;
        });
        var preference = new AssistantAccountSettings.Preference(OWNER, true, 1, AssistantAccountSettings.CONSENT_VERSION);
        when(mongo.findById(OWNER, AssistantAccountSettings.Preference.class)).thenReturn(preference);
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class))).thenReturn(true);
        when(mongo.findById(any(), eq(AssistantVoiceTurns.Claim.class)))
                .thenAnswer(invocation -> claims.get(invocation.getArgument(0)));
        when(mongo.insert(any(AssistantConversation.class))).thenAnswer(invocation -> {
            AssistantConversation row = invocation.getArgument(0);
            conversations.put(row.id(), row);
            return row;
        });
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            var row = conversations.get(query.getQueryObject().getString("id"));
            return row != null && row.owner().equals(query.getQueryObject().getString("owner")) ? row : null;
        });
        var settings = new AssistantAccountSettings(mongo);
        var readiness = new AssistantGeminiReadiness(true);
        var turns = new AssistantVoiceTurns(mongo, Clock.fixed(NOW, ZoneOffset.UTC), stt, selector,
                new AssistantVoiceActions(water, sleep), settings, readiness);
        mvc = MockMvcBuilders.standaloneSetup(new AssistantVoiceController(turns, settings, readiness)).build();
    }

    @Test
    void acceptsMultipartAndReplaysConversationWithoutAnotherRecognitionOrWrite() throws Exception {
        String transcript = "Ghi 250 ml nước lúc này";
        when(stt.transcribe(AUDIO)).thenReturn(transcript);
        when(selector.select(transcript)).thenReturn(new ToolCall("id", "function", "recordWater",
                "{\"amount\":\"250 ml\",\"when\":\"lúc này\"}"));
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("log", 250, NOW));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(recording("true", "audio/mp4", AUDIO))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.turns[0].text").value(transcript))
                    .andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED"));
        }
        assertThat(conversations).hasSize(1);
        assertThat(conversations.values().iterator().next().owner()).isEqualTo(OWNER);
        verify(stt, times(1)).transcribe(AUDIO);
        verify(selector, times(1)).select(transcript);
        verify(water, times(1)).create(OWNER, 250, NOW.toString());
        mvc.perform(recording("true", "audio/mp4", new byte[] {2})).andExpect(status().isConflict());
        verify(stt, never()).transcribe(new byte[] {2});
        verifyNoInteractions(sleep);
    }

    @Test
    void persistsFailedTurnWithOriginalTranscriptWhenVolumeIsMissing() throws Exception {
        String transcript = "Nhập lượng nước lúc 8 giờ sáng hôm nay cho tôi";
        when(stt.transcribe(AUDIO)).thenReturn(transcript);
        mvc.perform(recording("true", "audio/mp4", AUDIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].text").value(transcript))
                .andExpect(jsonPath("$.turns[0].outcome").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.turns[0].parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Chưa thể ghi lượng nước")));
        verify(selector).select(transcript);
        verifyNoInteractions(water, sleep);
    }

    @Test
    void refusesUnconsentedAndUnsupportedAudioBeforeRecognition() throws Exception {
        mvc.perform(recording("false", "audio/mp4", AUDIO)).andExpect(status().isForbidden());
        mvc.perform(multipart(PATH).file(new MockMultipartFile("audio", "voice.m4a", "audio/mp4", AUDIO))
                .param("turnId", turnId.toString()).param("consentAccepted", "true")
                .principal(new TestingAuthenticationToken(OWNER, ""))).andExpect(status().isForbidden());
        mvc.perform(recording("true", "text/plain", AUDIO)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(recording("true", "audio/mp4", new byte[0])).andExpect(status().isBadRequest());
        assertThat(claims).isEmpty();
        verifyNoInteractions(stt, selector, water, sleep);
    }

    @Test
    void accountLevelOptOutRejectsVoiceBeforeSttAndClaimCreation() throws Exception {
        when(mongo.findById(OWNER, AssistantAccountSettings.Preference.class)).thenReturn(null);

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isForbidden());

        assertThat(claims).isEmpty();
        verifyNoInteractions(stt, selector, water, sleep);
    }

    @Test
    void providerReadinessRejectsVoiceBeforeSttAndClaimCreation() throws Exception {
        var settings = new AssistantAccountSettings(mongo);
        var readiness = new AssistantGeminiReadiness(false);
        var turns = new AssistantVoiceTurns(mongo, Clock.fixed(NOW, ZoneOffset.UTC), stt, selector,
                new AssistantVoiceActions(water, sleep), settings, readiness);
        var unavailable = MockMvcBuilders.standaloneSetup(
                new AssistantVoiceController(turns, settings, readiness)).build();

        unavailable.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isServiceUnavailable());

        assertThat(claims).isEmpty();
        verifyNoInteractions(stt, selector, water, sleep);
    }

    @Test
    void accountRevocationDuringModelSelectionPreventsTheMetricWrite() throws Exception {
        String transcript = "Ghi 250 ml nước lúc này";
        var allowed = new AssistantAccountSettings.Preference(OWNER, true, 1, AssistantAccountSettings.CONSENT_VERSION);
        var revoked = new AssistantAccountSettings.Preference(OWNER, false, 2, null);
        var settingsChanged = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(mongo.findById(OWNER, AssistantAccountSettings.Preference.class))
                .thenAnswer(invocation -> settingsChanged.get() ? revoked : allowed);
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class)))
                .thenAnswer(invocation -> !settingsChanged.get());
        when(stt.transcribe(AUDIO)).thenReturn(transcript);
        when(selector.select(transcript)).thenAnswer(invocation -> {
            settingsChanged.set(true);
            return new ToolCall("id", "function", "recordWater", "{\"amount\":\"250 ml\"}");
        });

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isForbidden());

        verify(water, never()).create(any(), any(), any());
        assertThat(conversations).isEmpty();
    }

    @Test
    void refusesReplayWhenTheAccountConsentGenerationChanged() throws Exception {
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                new AssistantAccountSettings.Preference(OWNER, true, 1, AssistantAccountSettings.CONSENT_VERSION));
        when(mongo.findById(OWNER, AssistantAccountSettings.Preference.class)).thenAnswer(invocation -> current.get());
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class)))
                .thenAnswer(invocation -> current.get().personalDataAllowed());
        when(stt.transcribe(AUDIO)).thenReturn("Ghi nhật ký nước");

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isOk());
        current.set(new AssistantAccountSettings.Preference(OWNER, true, 2, AssistantAccountSettings.CONSENT_VERSION));

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isConflict());

        assertThat(conversations).hasSize(1);
        verify(stt, times(1)).transcribe(AUDIO);
    }

    private MockMultipartHttpServletRequestBuilder recording(String consent, String contentType, byte[] bytes) {
        return (MockMultipartHttpServletRequestBuilder) multipart(PATH)
                .file(new MockMultipartFile("audio", "voice.m4a", contentType, bytes))
                .param("turnId", turnId.toString()).param("consentAccepted", consent)
                .param("consentVersion", "assemblyai-us-v1")
                .principal(new TestingAuthenticationToken(OWNER, ""));
    }
}
