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
import static com.vitaloom.backend.assistant.AssistantScriptedChat.call;
import static com.vitaloom.backend.assistant.AssistantScriptedChat.prose;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import com.vitaloom.backend.water.exception.WaterLogDuplicateException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
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
                            previous.expiresAt(), (AssistantCourseAnswers.Answer) fields.get("answer"), previous.journalAction()));
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
        when(mongo.exists(any(Query.class), eq(AssistantConversationTurns.Receipt.class))).thenReturn(true);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(invocation -> {
            AssistantConversation row = invocation.getArgument(0);
            conversations.put(row.id(), row);
            return row;
        });
        when(model.converse(anyList(), anyList(), any(), any())).thenReturn("Bạn uống bao nhiêu ml?");
        when(model.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW);
        configureControllers(model);
    }

    private void configureControllers(AssistantProvider selected) {
        var settings = new AssistantAccountSettings(mongo);
        var readiness = new AssistantGeminiReadiness(true);
        var conversationTurns = conversationTurns(settings, readiness, selected);
        var turns = new AssistantVoiceTurns(mongo, Clock.fixed(NOW, ZoneOffset.UTC), stt, conversationTurns, readiness);
        mvc = MockMvcBuilders.standaloneSetup(new AssistantVoiceController(turns, settings)).build();
        text = new AssistantCourseController(mongo, Clock.fixed(NOW, ZoneOffset.UTC), settings, conversationTurns);
    }

    @Test
    void acceptsMultipartAndReplaysConversationWithoutAnotherRecognitionOrWrite() throws Exception {
        String transcript = "Ghi 250 ml nước lúc này";
        when(stt.transcribe(AUDIO)).thenReturn(transcript);
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater",
                "{\"amountMl\":250}"));
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
        verify(model, times(1)).converse(anyList(), anyList(), any(), any());
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
                .andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED"))
                .andExpect(jsonPath("$.turns[0].parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("bao nhiêu ml")));
        verify(model).converse(anyList(), anyList(), any(), any());
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
                conversationTurns(settings, readiness, model), readiness);
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
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation -> {
            settingsChanged.set(true);
            return "Không được ghi.";
        });

        mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isConflict());

        verify(water, never()).create(any(), any(), any());
        assertThat(conversations.values()).allSatisfy(row -> assertThat(row.turns()).isEmpty());
    }

    @Test
    void appendsVoiceToTypedSleepClarificationAndRejectsCrossChannelTurnReuse() throws Exception {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenReturn("Bạn đã ngủ bao lâu?");
        text.turn(auth(), id, new AssistantCourseController.Send(UUID.randomUUID(), "Ghi giấc ngủ sáng nay, cảm giác mệt mỏi.", null));
        when(stt.transcribe(AUDIO)).thenReturn("6 tiếng rưỡi");
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(call -> {
            List<Message> messages = call.getArgument(0);
            assertThat(messages.stream().skip(1).map(Message::getText)).containsExactly("Ghi giấc ngủ sáng nay, cảm giác mệt mỏi.",
                    "Bạn đã ngủ bao lâu?", "6 tiếng rưỡi");
            return AssistantScriptedChat.tool("recordSleepDuration", "{\"sleepDate\":\"2026-11-18\",\"durationMinutes\":390,\"feeling\":\"TIRED\"}").answer(call);
        });
        when(sleep.create(eq(OWNER), any())).thenReturn(new SessionResponse("sleep", NOW, "MAIN", "DURATION", "TIRED",
                "2026-11-18", 390, null, null, null, null, null, true));
        mvc.perform(recording("true", "audio/mp4", AUDIO).param("conversationId", id.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.turns.length()").value(2))
                .andExpect(jsonPath("$.turns[1].parts[0].text").value(org.hamcrest.Matchers.containsString("390 phút")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> text.turn(auth(), id,
                new AssistantCourseController.Send(turnId, "6 tiếng rưỡi", null)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(sleep).create(eq(OWNER), any());
    }

    @Test
    void sleepDurationFromSpokenUnitsArrivesAs390NormalizedMinutes() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(
                AssistantScriptedChat.tool("recordSleepDuration", """
                        {"sleepDate":"2026-11-18","durationMinutes":390,"feeling":"TIRED"}
                        """));
        stubSleepResult();
        var send = new AssistantCourseController.Send(turnId,
                "Ghi giấc ngủ sáng nay, 6 tiếng 30p, cảm giác mệt mỏi.", null);

        assertThat(text.turn(auth(), id, send).turns().getFirst().parts().getFirst().text()).contains("390 phút");
        verifySleepDuration();
    }

    @Test
    void permissionRevokedDuringTheWritePolicyPreventsReservation() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any()))
                .thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250}"));
        when(model.checkPolicy(anyList(), any())).thenAnswer(invocation -> {
            when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class))).thenReturn(false);
            return AssistantScriptedChat.ALLOW;
        });
        var send = new AssistantCourseController.Send(turnId, "Ghi 250 ml nước", null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> text.turn(auth(), id, send))
                .isInstanceOf(AssistantCourseController.AccountSettingChangedException.class);
        assertThat(receipts).isEmpty();
        verifyNoInteractions(water, sleep);
    }

    @Test
    void cancelledRequestReachesThePolicyWithItsActualRoles() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenReturn("Bạn đã ngủ bao lâu?");
        text.turn(auth(), id, new AssistantCourseController.Send(UUID.randomUUID(),
                "Ghi giấc ngủ sáng nay, cảm giác mệt mỏi", null));
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(
                AssistantScriptedChat.tool("recordSleepDuration", """
                        {"sleepDate":"2026-11-18","durationMinutes":390,"feeling":"TIRED"}
                        """));
        when(model.checkPolicy(anyList(), any())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            assertThat(messages.getLast().getText())
                    .contains("Ghi giấc ngủ sáng nay", "Bạn đã ngủ bao lâu?", "6 tiếng rưỡi nhưng đừng ghi", "USER", "ASSISTANT");
            return "{\"verdict\":\"UNSUPPORTED\",\"sources\":[]}";
        });

        var send = new AssistantCourseController.Send(turnId, "6 tiếng rưỡi nhưng đừng ghi", null);
        assertThat(text.turn(auth(), id, send).turns().getLast().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(water, sleep);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASSISTANT_SYNTHETIC_E2E", matches = "true")
    void realGeminiContinuesAClarificationFromTextToVoice() throws Exception {
        try (var gemini = liveProvider()) {
            configureControllers(gemini);
            stubSleepResult();
            var id = UUID.fromString(text.create(auth(), null).getBody().id());
            var question = text.turn(auth(), id, new AssistantCourseController.Send(UUID.randomUUID(),
                    "Ghi giấc ngủ sáng nay, cảm giác mệt mỏi.", null)).turns().getFirst();
            assertThat(question.parts().getFirst().text()).contains("?");
            verifyNoInteractions(sleep, water);
            when(stt.transcribe(AUDIO)).thenReturn("6 tiếng rưỡi");
            mvc.perform(recording("true", "audio/mp4", AUDIO).param("conversationId", id.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turns[1].parts[0].text").value(org.hamcrest.Matchers.containsString("390 phút")));
            verifySleepDuration();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASSISTANT_SYNTHETIC_E2E", matches = "true")
    void realGeminiNormalizesDateAndTimeForAnOvernightVoiceRecord() throws Exception {
        try (var gemini = liveProvider()) {
            configureControllers(gemini);
            when(sleep.create(eq(OWNER), any())).thenReturn(new SessionResponse("synthetic", NOW, "MAIN", "RANGE",
                    "TIRED", "2026-11-18", 390, null, null, null, null, null, true));
            when(stt.transcribe(AUDIO)).thenReturn("Ghi giấc ngủ từ 11 giờ 41 tối ngày 17 tháng 11 năm 2026 "
                    + "đến 6 giờ 11 sáng ngày 18 tháng 11 năm 2026, cảm giác mệt mỏi.");
            mvc.perform(recording("true", "audio/mp4", AUDIO)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.turns[0].parts[0].text").value(org.hamcrest.Matchers.containsString("390 phút")));
            var write = ArgumentCaptor.forClass(SessionWrite.class);
            verify(sleep).create(eq(OWNER), write.capture());
            assertThat(write.getValue()).isEqualTo(new SessionWrite("MAIN", "RANGE", "TIRED", "Asia/Ho_Chi_Minh",
                    new SessionWrite.CivilTime("2026-11-17", "23", "41", null),
                    new SessionWrite.CivilTime("2026-11-18", "06", "11", null), null, null));
        }
    }

    private void stubSleepResult() {
        when(sleep.create(eq(OWNER), any())).thenReturn(new SessionResponse("synthetic", NOW, "MAIN", "DURATION",
                "TIRED", "2026-11-18", 390, null, null, null, null, null, true));
    }

    private void verifySleepDuration() {
        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq(OWNER), write.capture());
        assertThat(write.getValue()).isEqualTo(new SessionWrite("MAIN", "DURATION", "TIRED", null,
                null, null, "2026-11-18", tools.jackson.databind.node.IntNode.valueOf(390)));
    }

    private static GeminiAssistantProvider liveProvider() {
        String key = System.getenv("ASSISTANT_GEMINI_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank(), "Missing configured Gemini key");
        return new GeminiAssistantProvider(key,
                System.getenv().getOrDefault("ASSISTANT_GEMINI_CHAT_MODEL", "gemini-3.1-flash-lite"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void preservesTheReceiptWhenGeminiTimesOutOrMisstatesTheSavedAmount(boolean timeout) {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("log", 250, NOW));
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("recordWater", "{\"amountMl\":250}")), prompt -> {
                    assertThat(receipts.get(OWNER + ":" + turnId).answer().parts().getFirst().text())
                            .contains("Đã ghi 250 ml");
                    if (timeout) throw new IllegalStateException("simulated provider timeout");
                    return prose("Đã ghi 900 ml.");
                }));
        var send = new AssistantCourseController.Send(turnId, "Ghi giúp mình 250 ml", null);

        assertThat(text.turn(auth(), id, send).turns().getFirst().parts().getFirst().text())
                .contains("Đã ghi 250 ml").doesNotContain("900");
        assertThat(text.turn(auth(), id, send).turns()).hasSize(1);
        verify(water).create(OWNER, 250, NOW.toString());
    }

    @Test
    void keepsTheKnownRejectionEvenIfGeminiClaimsSuccess() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(water.create(OWNER, 250, NOW.toString())).thenThrow(new WaterLogDuplicateException());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("recordWater", "{\"amountMl\":250}")), prompt ->
                        prose("Đã ghi 250 ml.")));

        var turn = text.turn(auth(), id, new AssistantCourseController.Send(turnId, "Ghi 250 ml", null))
                .turns().getFirst();
        assertThat(turn.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(turn.parts().getFirst().text()).contains("đã có bản ghi").doesNotContain("Đã ghi 250 ml");
        verify(water).create(OWNER, 250, NOW.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void medicalSafetyTakesPrecedenceOverMalformedOrMixedWriteFeedback(boolean mixed) {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        var calls = mixed ? List.of(call("recordWater", "{\"amountMl\":250}"), call("readWaterDay", "{}"))
                : List.of(call("recordSleepDuration", "{\"sleepDate\":\"2026-11-18\",\"durationMinutes\":390}"));
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, calls, prompt -> prose("Mình đã nhận yêu cầu.")));
        when(model.checkPolicy(anyList(), any())).thenReturn("{\"verdict\":\"URGENT\",\"sources\":[]}");

        var send = new AssistantCourseController.Send(turnId, "Tôi đau ngực và rất khó thở, ghi nhật ký giúp tôi", null);
        var result = text.turn(auth(), id, send).turns().getFirst();

        assertThat(result.outcome()).isEqualTo("SAFETY");
        assertThat(result.parts().getFirst().text()).contains("cấp cứu");
        verifyNoInteractions(water, sleep);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"recordWater", "readWaterDay"})
    void rejectsAnEntireMixedOrMultipleWriteBatch(String secondTool) {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation,
                        List.of(call("recordWater", "{\"amountMl\":250}"), call(secondTool, "{}")),
                        prompt -> { throw new AssertionError("Batch should never execute"); }));

        var turn = text.turn(auth(), id, new AssistantCourseController.Send(turnId, "Ghi và xem lượng nước", null))
                .turns().getFirst();
        assertThat(turn.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(water, sleep);
    }

    @Test
    void aSecondWriteInALaterNativeRoundCannotOverrideTheFirst() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("log", 250, NOW));
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("recordWater", "{\"amountMl\":250}")), prompt ->
                        AssistantScriptedChat.response(List.of(call("recordSleepDuration", "{}")), "")));

        assertThat(text.turn(auth(), id, new AssistantCourseController.Send(turnId, "Ghi 250 ml", null))
                .turns().getFirst().parts().getFirst().text()).contains("Đã ghi 250 ml");
        verify(water).create(OWNER, 250, NOW.toString());
        verifyNoInteractions(sleep);
    }

    @Test
    void aDatabaseFailureAfterReservationNeverResubmitsTheDomainCreate() throws Exception {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(water.create(OWNER, 250, NOW.toString()))
                .thenThrow(new DataAccessResourceFailureException("create acknowledgement missing"));
        when(model.converse(anyList(), anyList(), any(), any()))
                .thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250}"));
        var http = MockMvcBuilders.standaloneSetup(text).build();
        var send = post("/api/assistant/mvp/conversations/" + id + "/turns").principal(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"turnId\":\"" + turnId + "\",\"text\":\"Ghi 250 ml\"}");
        http.perform(send).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_WRITE_UNCERTAIN"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("kiểm tra nhật ký")));
        http.perform(send).andExpect(status().isConflict());
        assertThat(receipts.get(OWNER + ":" + turnId).answer()).isNull();
        verify(water).create(OWNER, 250, NOW.toString());
        verify(model).converse(anyList(), anyList(), any(), any());
    }

    @Test
    void recoversTextWriteAfterFailedConversationSaveWithoutAnotherMetricWrite() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250}"));
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
        verify(model).converse(anyList(), anyList(), any(), any());
    }

    @Test
    void doesNotCreateIfReceiptReservationFails() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250}"));
        when(mongo.insert(any(AssistantConversationTurns.Receipt.class)))
                .thenThrow(new DataAccessResourceFailureException("reservation unavailable"));
        var send = new AssistantCourseController.Send(turnId, "Log giúp mình 250 ml", null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> text.turn(auth(), id, send))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(water, sleep);
    }

    @Test
    void recoversTheReceiptWhenSettlementAcknowledgementIsLost() {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250}"));
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("log", 250, NOW));
        org.mockito.Mockito.doAnswer(invocation -> {
            var receiptId = OWNER + ":" + turnId;
            var claim = receipts.get(receiptId);
            org.springframework.data.mongodb.core.query.Update update = invocation.getArgument(1);
            var fields = (org.bson.Document) update.getUpdateObject().get("$set");
            receipts.put(receiptId, new AssistantConversationTurns.Receipt(claim.id(), claim.conversationId(),
                    claim.revision(), claim.inputKey(), claim.generation(), claim.at(), claim.claimedAt(),
                    claim.expiresAt(), (AssistantCourseAnswers.Answer) fields.get("answer"), claim.journalAction()));
            throw new DataAccessResourceFailureException("settlement acknowledgement lost");
        }).when(mongo).updateFirst(any(Query.class), any(), eq(AssistantConversationTurns.Receipt.class));
        var send = new AssistantCourseController.Send(turnId, "Log giúp mình 250 ml", null);

        assertThat(text.turn(auth(), id, send).turns().getFirst().parts().getFirst().text()).contains("Đã ghi 250 ml");
        assertThat(text.turn(auth(), id, send).turns()).hasSize(1);
        verify(water).create(OWNER, 250, NOW.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void closesAnIndeterminateTurnWithoutReexecutingIt(Boolean journalAction) {
        var id = UUID.fromString(text.create(auth(), null).getBody().id());
        String originalText = "Log giúp mình 250 ml";
        String receiptId = OWNER + ":" + turnId;
        receipts.put(receiptId, new AssistantConversationTurns.Receipt(receiptId, id.toString(), null,
                AssistantConversationTurns.textKey(originalText), 1L, NOW.minusSeconds(200), NOW.minusSeconds(190),
                NOW.plusSeconds(60), null, journalAction));

        var answer = text.turn(auth(), id, new AssistantCourseController.Send(turnId, originalText, null));

        assertThat(answer.turns().getFirst().outcome()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        String text = answer.turns().getFirst().parts().getFirst().text();
        if (Boolean.FALSE.equals(journalAction)) {
            assertThat(text).contains("thử lại sau").doesNotContain("kiểm tra nhật ký");
        } else {
            assertThat(text).contains("kiểm tra nhật ký");
        }
        verifyNoInteractions(model, water, sleep);
    }

    private AssistantConversationTurns conversationTurns(AssistantAccountSettings settings, AssistantGeminiReadiness readiness,
            AssistantProvider selected) {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AssistantConversationTurns(mongo, clock,
                new AssistantCourseAnswers(sleep, mock(SleepRecentStatisticsService.class), water, clock),
                providerOf(null), providerOf(selected), readiness, settings, water, sleep);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
        };
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
