package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
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
    private final AssistantProvider model = mock(AssistantProvider.class);
    private final WaterService water = mock(WaterService.class);
    private final SleepJournalService sleep = mock(SleepJournalService.class);
    private final Map<String, AssistantVoiceTurns.Claim> claims = new HashMap<>();
    private final Map<String, AssistantConversation> conversations = new HashMap<>();
    private final Map<String, AssistantConversationTurns.Receipt> receipts = new HashMap<>();
    private final UUID turnId = UUID.randomUUID();
    private MockMvc mvc;
    private AssistantCourseController text;

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
        when(mongo.findById(any(), eq(AssistantConversationTurns.Receipt.class)))
                .thenAnswer(invocation -> receipts.get(invocation.getArgument(0)));
        when(mongo.insert(any(AssistantConversationTurns.Receipt.class))).thenAnswer(invocation -> {
            AssistantConversationTurns.Receipt receipt = invocation.getArgument(0);
            if (receipts.putIfAbsent(receipt.id(), receipt) != null) throw new DuplicateKeyException("Duplicate receipt");
            return receipt;
        });
        when(mongo.updateFirst(any(Query.class), any(), eq(AssistantConversationTurns.Receipt.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    org.springframework.data.mongodb.core.query.Update update = invocation.getArgument(1);
                    String id = query.getQueryObject().getString("id");
                    var previous = receipts.get(id);
                    var fields = (org.bson.Document) update.getUpdateObject().get("$set");
                    receipts.put(id, new AssistantConversationTurns.Receipt(id, previous.conversationId(),
                            previous.revision(), previous.inputKey(), previous.generation(), previous.at(), previous.claimedAt(),
                            previous.expiresAt(), (AssistantCourseAnswers.Answer) fields.get("answer")));
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });
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
        when(mongo.exists(any(Query.class), eq(AssistantConversation.class))).thenReturn(true);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(invocation -> {
            AssistantConversation row = invocation.getArgument(0);
            conversations.put(row.id(), row);
            return row;
        });
        when(model.converse(anyList(), anyList(), any())).thenReturn(writeClarification("Bạn uống bao nhiêu ml?"));
        var settings = new AssistantAccountSettings(mongo);
        var readiness = new AssistantGeminiReadiness(true);
        var conversationTurns = conversationTurns(settings, readiness);
        var turns = new AssistantVoiceTurns(mongo, Clock.fixed(NOW, ZoneOffset.UTC), stt, conversationTurns, readiness);
        mvc = MockMvcBuilders.standaloneSetup(new AssistantVoiceController(turns, settings)).build();
        text = new AssistantCourseController(mongo, Clock.fixed(NOW, ZoneOffset.UTC), settings, conversationTurns);
    }

    @Test
    void acceptsMultipartAndReplaysConversationWithoutAnotherRecognitionOrWrite() throws Exception {
        String transcript = "Ghi 250 ml nước lúc này";
        when(stt.transcribe(AUDIO)).thenReturn(transcript);
        when(model.converse(anyList(), anyList(), any())).thenReturn(action("recordWater",
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
        verify(model, times(1)).converse(anyList(), anyList(), any());
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
                .andExpect(jsonPath("$.turns[0].outcome").value("WRITE_CLARIFICATION"))
                .andExpect(jsonPath("$.turns[0].parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("bao nhiêu ml")));
        verify(model).converse(anyList(), anyList(), any());
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
        verifyNoInteractions(stt, model, water, sleep);
    }

    @Test
    void accountLevelOptOutRejectsVoiceBeforeSttAndClaimCreation() throws Exception {
        when(mongo.findById(OWNER, AssistantAccountSettings.Preference.class)).thenReturn(null);

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isForbidden());

        assertThat(claims).isEmpty();
        verifyNoInteractions(stt, model, water, sleep);
    }

    @Test
    void providerReadinessRejectsVoiceBeforeSttAndClaimCreation() throws Exception {
        var settings = new AssistantAccountSettings(mongo);
        var readiness = new AssistantGeminiReadiness(false);
        var turns = new AssistantVoiceTurns(mongo, Clock.fixed(NOW, ZoneOffset.UTC), stt,
                conversationTurns(settings, readiness), readiness);
        var unavailable = MockMvcBuilders.standaloneSetup(
                new AssistantVoiceController(turns, settings)).build();

        unavailable.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isServiceUnavailable());

        assertThat(conversations).isEmpty();
        verifyNoInteractions(stt, model, water, sleep);
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
        when(model.converse(anyList(), anyList(), any())).thenAnswer(invocation -> {
            settingsChanged.set(true);
            return action("recordWater", "{\"amount\":\"250 ml\"}");
        });

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isConflict());

        verify(water, never()).create(any(), any(), any());
        assertThat(conversations.values()).allSatisfy(row -> assertThat(row.turns()).isEmpty());
    }

    @Test
    void appendsVoiceToTypedSleepClarificationAndRejectsCrossChannelTurnReuse() throws Exception {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any())).thenReturn(writeClarification("Bạn thấy thế nào khi thức dậy?"));
        text.turn(auth(), id, new AssistantCourseController.Send(UUID.randomUUID(), "Ngủ 7 tiếng hôm nay", null));
        when(stt.transcribe(AUDIO)).thenReturn("Bình thường");
        when(model.converse(anyList(), anyList(), any())).thenAnswer(call -> {
            List<Message> messages = call.getArgument(0);
            assertThat(messages.stream().skip(1).map(Message::getText)).containsExactly("Ngủ 7 tiếng hôm nay",
                    "Bạn thấy thế nào khi thức dậy?", "Bình thường");
            return action("recordSleep", "{\"duration\":\"7 tiếng\",\"day\":\"hôm nay\",\"feeling\":\"Bình thường\"}");
        });
        when(sleep.create(eq(OWNER), any())).thenReturn(new SessionResponse("sleep", NOW, "MAIN", null, null,
                "2026-11-18", 420, null, null, null, null, null, true));
        mvc.perform(recording("true", "audio/mp4", AUDIO).param("conversationId", id.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.turns.length()").value(2))
                .andExpect(jsonPath("$.turns[1].parts[0].text").value(org.hamcrest.Matchers.containsString("420 phút")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> text.turn(auth(), id,
                new AssistantCourseController.Send(turnId, "Bình thường", null)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(sleep).create(eq(OWNER), any());
    }

    @Test
    void recoversTextWriteAfterFailedConversationSaveWithoutAnotherMetricWrite() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any())).thenReturn(action("recordWater", "{\"amount\":\"250 ml\"}"));
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("log", 250, NOW));
        when(mongo.save(any(AssistantConversation.class))).thenThrow(new DataAccessResourceFailureException("retry"))
                .thenAnswer(invocation -> {
                    AssistantConversation row = invocation.getArgument(0);
                    conversations.put(row.id(), row);
                    return row;
                });
        var send = new AssistantCourseController.Send(turnId, "Log giúp mình 250 ml", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> text.turn(auth(), id, send))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(text.turn(auth(), id, send).turns().getFirst().parts().getFirst().text()).contains("250 ml");
        verify(water).create(OWNER, 250, NOW.toString());
        verify(model).converse(anyList(), anyList(), any());
    }

    @Test
    void closesAnIndeterminateTurnWithoutReexecutingIt() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        String originalText = "Log giúp mình 250 ml";
        String receiptId = OWNER + ":" + turnId;
        receipts.put(receiptId, new AssistantConversationTurns.Receipt(receiptId, id.toString(), null,
                AssistantConversationTurns.textKey(originalText), 1L, NOW.minusSeconds(200), NOW.minusSeconds(190),
                NOW.plusSeconds(60), null));

        var answer = text.turn(auth(), id, new AssistantCourseController.Send(turnId, originalText, null));

        assertThat(answer.turns().getFirst().outcome()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(answer.turns().getFirst().parts().getFirst().text()).contains("kiểm tra nhật ký");
        verifyNoInteractions(model, water, sleep);
    }

    private AssistantConversationTurns conversationTurns(AssistantAccountSettings settings, AssistantGeminiReadiness readiness) {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AssistantConversationTurns(mongo, clock,
                new AssistantCourseAnswers(sleep, mock(SleepRecentStatisticsService.class), water, clock),
                providerOf(null), providerOf(model), readiness, settings, water, sleep);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
        };
    }

    private static AssistantGeminiTurn.Result writeClarification(String question) {
        return new AssistantGeminiTurn.Result("""
                {"parts":[{"kind":"WRITE_CLARIFICATION","text":"%s","sources":[]}]}
                """.formatted(question), AssistantGeminiTurn.Grounding.OPEN);
    }

    private static AssistantGeminiTurn.Result action(String name, String arguments) {
        return new AssistantGeminiTurn.Result(null, AssistantGeminiTurn.Grounding.OPEN,
                new ToolCall("id", "function", name, arguments));
    }

    private static TestingAuthenticationToken auth() {
        return new TestingAuthenticationToken(OWNER, "");
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
