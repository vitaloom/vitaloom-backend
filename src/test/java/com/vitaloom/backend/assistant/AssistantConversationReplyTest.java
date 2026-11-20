package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.vitaloom.backend.assistant.AssistantScriptedChat.call;
import static com.vitaloom.backend.assistant.AssistantScriptedChat.prose;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;

class AssistantConversationReplyTest {
    private static final String OWNER = "owner-with-synthetic-data";
    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final String DATE = "2026-11-18";
    private static final String WATER_ARGS = "{\"date\":\"2026-11-18\"}";
    private static final String FAQ_ARGS = "{\"query\":\"Câu hỏi mô phỏng?\",\"healthEducation\":true}";
    private static final AssistantCourseAnswers.Source FAQ = new AssistantCourseAnswers.Source(
            "HEALTH-FIXTURE-001", 1, "Tài liệu mô phỏng", "", null, null);
    private final AssistantProvider provider = mock(AssistantProvider.class);
    private final SleepJournalService journal = mock(SleepJournalService.class);
    private final SleepRecentStatisticsService sleep = mock(SleepRecentStatisticsService.class);
    private final WaterService water = mock(WaterService.class);
    private final AssistantFixtureSearch search = mock(AssistantFixtureSearch.class);
    private final AssistantConversationalAnswers answers = new AssistantConversationalAnswers(provider, search,
            new AssistantCourseAnswers(journal, sleep, water, Clock.fixed(NOW, ZoneOffset.UTC)));

    @BeforeEach
    void allowOrdinaryProse() {
        when(provider.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW);
    }

    @Test
    void publishesOrdinaryClarificationWithoutAResponseProtocol() {
        when(provider.converse(anyList(), anyList(), any(), any())).thenReturn("Bạn đã ngủ bao lâu?");
        var reply = ask("Ghi giấc ngủ sáng nay", () -> true);
        assertThat(reply.outcome()).isEqualTo("ANSWERED");
        assertThat(reply.parts()).containsExactly(new AssistantCourseAnswers.Part("ANSWERED", "Bạn đã ngủ bao lâu?", null));
        verifyNoInteractions(water, journal, sleep, search);
    }

    @Test
    void bindsHybridProseToTheFreshPersonalAndApprovedSources() {
        prepareFaq();
        when(water.dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh")).thenReturn(new DailyWaterSummaryResponse(
                DATE, 2000, 250, .125, List.of(new WaterLogResponse("synthetic", 250, NOW))));
        String text = "Nhật ký có 250 ml. Tài liệu mô phỏng nêu cách xem dữ liệu.";
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("readWaterDay", WATER_ARGS),
                        call("searchApprovedKnowledge", FAQ_ARGS)), prompt -> prose(text)));
        when(provider.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW)
                .thenAnswer(invocation -> {
                    List<Message> messages = invocation.getArgument(0);
                    assertThat(messages.getLast().getText())
                            .contains("PERSONAL", "HEALTH_EDUCATION", "recordedMl", "250", "Nội dung mô phỏng");
                    return verdict("ALLOW", "\"e1\",\"e2\"");
                });

        var reply = ask("Tôi ghi bao nhiêu nước và tài liệu nói gì?", () -> true);

        assertThat(reply.parts()).extracting(AssistantCourseAnswers.Part::text).containsExactly(text, "");
        assertThat(reply.parts()).extracting(part -> part.source().id()).containsExactly("water-day", FAQ.id());
        assertThat(reply.parts().getFirst().source().route()).isEqualTo("/water/progress");
        assertThat(reply.parts().getFirst().source().from()).isEqualTo(DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Các ghi chép của bạn cho thấy điều này.", "Đã ghi 250 ml.", "Hãy điều trị bằng thuốc X."})
    void suppressesUnsupportedOutputWithoutRetrievalOrReceipt(String unsupported) {
        when(provider.converse(anyList(), anyList(), any(), any())).thenReturn(unsupported);
        when(provider.checkPolicy(anyList(), any())).thenReturn(verdict("UNSUPPORTED", ""));
        var reply = ask("Bạn có thể giúp gì?", () -> true);
        assertThat(reply.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(reply.parts().getFirst().text()).doesNotContain(unsupported);
    }

    @Test
    void unrelatedApprovedKnowledgeCannotLicenseTheProse() {
        prepareFaq();
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("searchApprovedKnowledge", FAQ_ARGS)), prompt ->
                        prose("Khuyến nghị mô phỏng không có trong tài liệu.")));
        when(provider.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW, verdict("UNSUPPORTED", ""));

        var reply = ask("Hãy đưa ra khuyến nghị", () -> true);

        assertThat(reply.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(reply.parts().getFirst().source()).isNull();
    }

    @Test
    void refusesHealthProseIfApprovalWasWithdrawnDuringReview() {
        var evidence = prepareFaq();
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("searchApprovedKnowledge", FAQ_ARGS)), prompt ->
                        prose("Nội dung mô phỏng.")));
        when(provider.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW).thenAnswer(invocation -> {
            when(evidence.current()).thenReturn(false);
            return verdict("ALLOW", "\"e1\"");
        });

        var reply = ask("Bạn tìm thấy gì?", () -> true);

        assertThat(reply.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(reply.parts().getFirst().source()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"URGENT", "MEDICAL_RESTRICTED"})
    void safetyReviewVetoesTheWholeReadBatch(String code) {
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("readWaterDay", WATER_ARGS),
                        call("searchApprovedKnowledge", FAQ_ARGS)), prompt -> { throw new AssertionError(); }));
        when(provider.checkPolicy(anyList(), any())).thenReturn(verdict(code, ""));

        var reply = ask("Yêu cầu y tế mô phỏng", () -> true);

        assertThat(reply.outcome()).isEqualTo("SAFETY");
        assertThat(reply.parts().getFirst().text()).contains(code.equals("URGENT") ? "cấp cứu" : "kê đơn");
        verifyNoInteractions(water, journal, sleep, search);
    }

    @Test
    void refusesPrivateQueriesBeforeEmbedding() {
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("searchApprovedKnowledge",
                        "{\"query\":\"Tìm hồ sơ cá nhân ngày 18/11 và 250 ml\",\"healthEducation\":true}")),
                        prompt -> { throw new AssertionError(); }));
        when(provider.checkPolicy(anyList(), any())).thenReturn(verdict("UNSUPPORTED", ""));

        assertThat(ask("Tìm thông tin", () -> true).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(search);
    }

    @Test
    void prohibitsProviderFollowUpWhenAccessIsRevokedDuringPersonalRead() {
        var current = new AtomicBoolean(true);
        when(water.dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh")).thenAnswer(invocation -> {
            current.set(false);
            return new DailyWaterSummaryResponse(DATE, 2000, 250, .125,
                    List.of(new WaterLogResponse("synthetic", 250, NOW)));
        });
        when(provider.converse(anyList(), anyList(), any(), any())).thenAnswer(invocation ->
                AssistantScriptedChat.run(invocation, List.of(call("readWaterDay", WATER_ARGS)),
                        prompt -> { throw new AssertionError("Evidence must not reach Gemini"); }));

        assertThatThrownBy(() -> ask("Hôm nay tôi ghi bao nhiêu nước?", current::get))
                .isInstanceOf(AssistantNativeChat.AccessChanged.class);
    }

    @Test
    void returnsNeutralTechnicalCopyWhenTheProviderFails() {
        when(provider.converse(anyList(), anyList(), any(), any())).thenThrow(new IllegalStateException("private"));
        var reply = ask("Giấc ngủ gần đây của tôi", () -> true);

        assertThat(reply.outcome()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(reply.parts().getFirst().text()).contains("thử lại sau").doesNotContain("cấp cứu", "private");
        verifyNoInteractions(water, journal, sleep, search);
    }

    private AssistantFixtureSearch.ReadyEvidence prepareFaq() {
        var item = mock(AssistantFixtureSearch.ReadyEvidence.class);
        when(item.evidence()).thenReturn(new AssistantProvider.FaqEvidence(
                FAQ.id(), "Câu hỏi mô phỏng?", "Nội dung mô phỏng có nguồn."));
        when(item.source()).thenReturn(FAQ);
        when(item.healthEducation()).thenReturn(true);
        when(item.current()).thenReturn(true);
        when(search.retrieveReadyEvidence(any(), any())).thenReturn(List.of(item));
        return item;
    }

    private AssistantCourseAnswers.Answer ask(String text, BooleanSupplier current) {
        return answers.answer(OWNER, text, UUID.randomUUID(), NOW, List.of(), List.of(), true, current, null, null);
    }

    private static String verdict(String verdict, String sources) {
        return "{\"verdict\":\"" + verdict + "\",\"sources\":[" + sources + "]}";
    }
}
