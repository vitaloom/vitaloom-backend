package com.vitaloom.backend.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.support.MongoTestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(properties = {
    "vitaloom.test.mongodb.database=assistant_voice_test",
    "ASSISTANT_GEMINI_API_KEY=fixture-only-fake-key",
    "ASSISTANT_GEMINI_HEALTH_PROCESSING_VERIFIED=true",
    "ASSISTANT_VECTOR_MONGO_URI=",
    "ASSISTANT_VOICE_ENABLED=true",
    "ASSEMBLYAI_API_KEY=fixture-assembly-key"
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
    @Autowired MongoTemplate mongo;
    @MockitoBean AssistantSpeechToText stt;
    @MockitoBean GeminiAssistantProvider gemini;

    @BeforeEach
    void allowSafeRequests() {
        when(gemini.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW);
    }

    @Test
    void conversationUsesOneVersionedPermissionAcrossDisableAndReenable() throws Exception {
        String owner = "chat-" + UUID.randomUUID();
        String conversations = "/api/assistant/mvp/conversations";
        mvc.perform(post(conversations).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        changePermission(owner, true);
        var creation = mvc.perform(post(conversations).header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(creation.getResponse().getContentAsString(), "$.id");
        when(gemini.converse(anyList(), anyList(), any(), any())).thenReturn("Chào bạn.");
        var turn = post(conversations + "/" + id + "/turns").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnId\":\"" + UUID.randomUUID() + "\",\"text\":\"Xin chào\"}");
        mvc.perform(turn).andExpect(status().isOk()).andExpect(jsonPath("$.turns[0].parts[0].text").value("Chào bạn."));
        var rowId = new org.bson.Document("_id", id);
        mongo.getCollection("assistant_course_conversations").updateOne(rowId, new org.bson.Document()
                .append("$unset", new org.bson.Document("turns.0.journalAction", ""))
                .append("$set", new org.bson.Document("turns.0.reply.outcome", "WRITE_CLARIFICATION")));

        changePermission(owner, false);
        mvc.perform(get(conversations + "/" + id).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        changePermission(owner, true);
        mvc.perform(get(conversations + "/" + id).header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1))
                .andExpect(jsonPath("$.turns[0].outcome").value("WRITE_CLARIFICATION"));

        mongo.getCollection("assistant_course_conversations").updateOne(rowId,
                new org.bson.Document("$set", new org.bson.Document("expiresAt", java.util.Date.from(Instant.now().minusSeconds(1)))));
        mvc.perform(turn).andExpect(status().isNotFound());
        verify(gemini).converse(anyList(), anyList(), any(), any());
    }

    @Test
    void writesThroughAuthenticatedOwnerServicesAndReplaysTheWaterTurn() throws Exception {
        String owner = "voice-" + UUID.randomUUID();
        String today = LocalDate.now(AssistantJournalActions.ZONE).toString();
        UUID waterTurn = UUID.randomUUID();
        mvc.perform(voice(waterTurn, WATER_AUDIO, true))
                .andExpect(status().isUnauthorized());
        mvc.perform(voice(waterTurn, WATER_AUDIO, false).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(voice(waterTurn, WATER_AUDIO, true).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stt, gemini);
        mongo.getCollection("assistant_account_settings").insertOne(new org.bson.Document("_id", owner)
                .append("personalDataAllowed", true).append("generation", 4L));
        mvc.perform(get("/api/assistant/mvp/settings").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.personalDataAllowed").value(false));
        changePermission(owner, true);

        String water = "Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi";
        when(stt.transcribe(WATER_AUDIO)).thenReturn(water);
        when(gemini.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater",
                "{\"amountMl\":250}"));
        String conversationId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(voice(waterTurn, WATER_AUDIO, true).header("Authorization", auth(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turns[0].text").value(water))
                    .andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED")).andReturn();
            conversationId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        }
        verify(gemini).converse(anyList(), anyList(), any(), any());
        mvc.perform(waterSummary(today, owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(250));
        mvc.perform(waterSummary(today, "other-" + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(0));

        String other = "other-" + owner;
        changePermission(other, true);
        mvc.perform(voice(UUID.randomUUID(), SLEEP_AUDIO, true).param("conversationId", conversationId)
                        .header("Authorization", auth(other))).andExpect(status().isNotFound());

        org.mockito.Mockito.doReturn("Khi thức dậy bạn cảm thấy thế nào?")
                .when(gemini).converse(anyList(), anyList(), any(), any());
        mvc.perform(post("/api/assistant/mvp/conversations/" + conversationId + "/turns")
                        .header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnId\":\"" + UUID.randomUUID() + "\",\"text\":\"Ghi 8 tiếng ngủ hôm nay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[1].outcome").value("ANSWERED"));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isEmpty());

        when(stt.transcribe(SLEEP_AUDIO)).thenReturn("Tốt");
        when(gemini.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordSleepDuration",
                "{\"sleepDate\":\"" + today + "\",\"durationMinutes\":480,\"feeling\":\"COMFORTABLE\"}"));
        UUID sleepTurn = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(voice(sleepTurn, SLEEP_AUDIO, true).param("conversationId", conversationId)
                            .header("Authorization", auth(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(conversationId))
                    .andExpect(jsonPath("$.turns.length()").value(3))
                    .andExpect(jsonPath("$.turns[2].outcome").value("ANSWERED"));
        }
        verify(gemini, times(3)).converse(anyList(), anyList(), any(), any());
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].day").value(today))
                .andExpect(jsonPath("$.sessions[0].minutes").value(480))
                .andExpect(jsonPath("$.sessions[0].feeling").value("COMFORTABLE"));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth("other-" + owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions").isEmpty());
        changePermission(owner, false);
        mvc.perform(voice(waterTurn, WATER_AUDIO, true).header("Authorization", auth(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(waterSummary(today, owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMl").value(250));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions[0].minutes").value(480));
    }

    @Test
    void competingTurnIdsCanOnlyCreateOneRecordAtTheSameRevision() throws Exception {
        String owner = "receipt-race-" + UUID.randomUUID();
        String conversations = "/api/assistant/mvp/conversations";
        changePermission(owner, true);
        var created = mvc.perform(post(conversations).header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        Instant reference = Instant.now().minusSeconds(60);
        var racing = new CyclicBarrier(2);
        when(gemini.checkPolicy(anyList(), any())).thenAnswer(invocation -> {
            racing.await(8, TimeUnit.SECONDS);
            return AssistantScriptedChat.ALLOW;
        });
        when(gemini.converse(anyList(), anyList(), any(), any())).thenAnswer(
                AssistantScriptedChat.tool("recordWater", "{\"amountMl\":250,\"loggedAt\":\"" + reference + "\"}"))
                .thenAnswer(AssistantScriptedChat.tool("recordWater",
                        "{\"amountMl\":250,\"loggedAt\":\"" + reference.minusSeconds(1) + "\"}"));

        try (var requests = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> send = () -> mvc.perform(
                            post(conversations + "/" + id + "/turns").header("Authorization", auth(owner))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"turnId\":\"" + UUID.randomUUID() + "\",\"text\":\"Ghi 250 ml nước\"}"))
                    .andReturn().getResponse().getStatus();
            var first = requests.submit(send);
            var second = requests.submit(send);
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        String day = reference.atZone(AssistantJournalActions.ZONE).toLocalDate().toString();
        mvc.perform(waterSummary(day, owner)).andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(250));
        var receipts = mongo.find(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("conversationId").is(id)),
                AssistantConversationTurns.Receipt.class);
        org.assertj.core.api.Assertions.assertThat(receipts).hasSize(1);
        var original = receipts.getFirst();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mongo.insert(new AssistantConversationTurns.Receipt(
                owner + ":" + UUID.randomUUID(), id, original.revision(), "other-input", original.generation(),
                original.at(), original.claimedAt(), original.expiresAt(), null)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void recordsBothJournalsFromTextAndScrubsRepliesWhenDeletingConversation() throws Exception {
        String owner = "text-journal-" + UUID.randomUUID();
        String conversations = "/api/assistant/mvp/conversations";
        changePermission(owner, true);
        var creation = mvc.perform(post(conversations).header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(creation.getResponse().getContentAsString(), "$.id");
        UUID waterTurn = UUID.randomUUID();
        when(gemini.converse(anyList(), anyList(), any(), any())).thenAnswer(AssistantScriptedChat.tool("recordWater", "{\"amountMl\":300}"))
                .thenAnswer(AssistantScriptedChat.tool("recordSleepDuration", "{\"sleepDate\":\""
                        + LocalDate.now(AssistantJournalActions.ZONE) + "\",\"durationMinutes\":420,\"feeling\":\"COMFORTABLE\"}"));
        var waterRequest = post(conversations + "/" + id + "/turns").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnId\":\"" + waterTurn + "\",\"text\":\"Log giúp mình 300 ml\"}");
        mvc.perform(waterRequest).andExpect(status().isOk()).andExpect(jsonPath("$.turns[0].outcome").value("ANSWERED"));
        mvc.perform(waterRequest).andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1));
        mvc.perform(post(conversations + "/" + id + "/turns").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnId\":\"" + UUID.randomUUID() + "\",\"text\":\"Ghi ngủ 7 tiếng hôm nay, thấy tốt\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turns[1].outcome").value("ANSWERED"));

        mvc.perform(delete(conversations + "/" + id).header("Authorization", auth(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(get(conversations + "/" + id).header("Authorization", auth(owner)))
                .andExpect(status().isNotFound());
        var receipts = mongo.getCollection("assistant_turn_receipts");
        org.assertj.core.api.Assertions.assertThat(receipts.countDocuments(new org.bson.Document("conversationId", id)
                .append("answer", new org.bson.Document("$exists", true)))).isZero();
        mvc.perform(waterSummary(LocalDate.now(AssistantJournalActions.ZONE).toString(), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMl").value(300));
        mvc.perform(get("/api/sleep/v2/sessions").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions[0].minutes").value(420));
        verify(gemini, times(2)).converse(anyList(), anyList(), any(), any());
        verifyNoInteractions(stt);
    }

    private void changePermission(String owner, boolean enabled) throws Exception {
        mvc.perform(put("/api/assistant/mvp/settings").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enabled ? """
                                {"personalDataAllowed":true,"consentVersion":"gemini-health-v1"}
                                """ : """
                                {"personalDataAllowed":false}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.personalDataAllowed").value(enabled));
    }

    private MockMultipartHttpServletRequestBuilder voice(UUID turnId, byte[] audio, boolean consent) {
        return multipart(PATH).file(new MockMultipartFile("audio", "voice.m4a", "audio/mp4", audio))
                .param("turnId", turnId.toString()).param("consentAccepted", Boolean.toString(consent))
                .param("consentVersion", "assemblyai-us-v1");
    }

    private MockHttpServletRequestBuilder waterSummary(String day, String owner) {
        return get("/api/water/daily-summary").param("date", day).param("timeZone", AssistantJournalActions.ZONE.getId())
                .header("Authorization", auth(owner));
    }

    private String auth(String account) {
        return "Bearer " + tokens.issueAccess(account, Instant.now());
    }
}
