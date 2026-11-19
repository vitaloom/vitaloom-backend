package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class AssistantConversationReplyTest {
    private static final AssistantCourseAnswers.Source SOURCE = new AssistantCourseAnswers.Source(
            "sleep-recent", 1, "Giấc ngủ gần đây", "/sleep/recent", "2026-11-12", "2026-11-18");

    @Test
    void publishesModelProseWithBackendProvenance() {
        String json = """
                {"parts":[{"kind":"GROUNDED","text":"Có 4 ngày được ghi nhận.","sources":["e1"]}]}
                """;
        var reply = AssistantConversationReply.parse(json, Map.of("e1",
                new AssistantConversationReply.EvidenceSource(SOURCE, () -> true)));
        assertThat(reply.outcome()).isEqualTo("ANSWERED");
        assertThat(reply.parts()).containsExactly(new AssistantCourseAnswers.Part(
                "ANSWERED", "Có 4 ngày được ghi nhận.", SOURCE));
    }

    @Test
    void rejectsUnseenAndRevokedCitations() {
        String json = """
                {"parts":[{"kind":"GROUNDED","text":"Có 4 ngày được ghi nhận.","sources":["e1"]}]}
                """;
        assertThatThrownBy(() -> AssistantConversationReply.parse(json, Map.of()))
                .isInstanceOf(AssistantConversationReply.InvalidReply.class);
        assertThatThrownBy(() -> AssistantConversationReply.parse(json, Map.of("e1",
                new AssistantConversationReply.EvidenceSource(SOURCE, () -> false))))
                .isInstanceOf(AssistantConversationReply.InvalidReply.class);
    }

    @Test
    void mapsMultipleSourcesWithoutRepeatingProseOrShowingInternalKeys() {
        var faq = new AssistantCourseAnswers.Source("HEALTH-SLEEP-001", 1, "Sleep duration", "", null, null);
        String json = """
                {"parts":[{"kind":"GROUNDED","text":"Bạn có thể đối chiếu với khuyến nghị theo độ tuổi [e2].",
                "sources":["e1","e2"]}]}
                """;
        var reply = AssistantConversationReply.parse(json, Map.of(
                "e1", new AssistantConversationReply.EvidenceSource(SOURCE, () -> true),
                "e2", new AssistantConversationReply.EvidenceSource(faq, () -> true)));

        assertThat(reply.parts()).extracting(AssistantCourseAnswers.Part::source).containsExactly(SOURCE, faq);
        assertThat(reply.parts()).extracting(AssistantCourseAnswers.Part::text)
                .containsExactly("Bạn có thể đối chiếu với khuyến nghị theo độ tuổi.", "");
    }

    @Test
    void handlesLongMalformedInlineCitationWithoutRejectingOrdinaryProse() {
        String malformed = " [e1,".repeat(250) + "not-a-citation";
        String prose = "Mình chưa có đủ thông tin" + malformed;
        String json = "{\"parts\":[{\"kind\":\"LIMITATION\",\"text\":"
                + new tools.jackson.databind.ObjectMapper().writeValueAsString(prose)
                + ",\"sources\":[]}]}";

        var reply = AssistantConversationReply.parse(json, Map.of());

        assertThat(reply.parts().getFirst().text()).isEqualTo(prose);
    }

    @Test
    void permitsConversationAndNaturalLimitationsWithoutCitations() {
        String json = """
                {"parts":[{"kind":"LIMITATION","text":"Mình chưa có đủ thông tin để trả lời câu này.","sources":[]}]}
                """;
        var reply = AssistantConversationReply.parse(json, Map.of());
        assertThat(reply.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(reply.parts().getFirst().text()).doesNotContain("NO_MATCH");
    }

    @Test
    void preservesWriteClarificationAsItsOwnOutcome() {
        String json = """
                {"parts":[{"kind":"WRITE_CLARIFICATION","text":"Bạn muốn ghi cho ngày nào?","sources":[]}]}
                """;

        var reply = AssistantConversationReply.parse(json, Map.of());

        assertThat(reply.outcome()).isEqualTo("WRITE_CLARIFICATION");
        assertThat(reply.parts()).containsExactly(new AssistantCourseAnswers.Part(
                "ANSWERED", "Bạn muốn ghi cho ngày nào?", null));
    }

    @Test
    void rejectsWriteClarificationCombinedWithOtherParts() {
        String json = """
                {"parts":[{"kind":"WRITE_CLARIFICATION","text":"Bạn muốn ghi cho ngày nào?","sources":[]},
                {"kind":"CONVERSATION","text":"Mình có thể giúp.","sources":[]}]}
                """;

        assertThatThrownBy(() -> AssistantConversationReply.parse(json, Map.of()))
                .isInstanceOf(AssistantConversationReply.InvalidReply.class);
    }

    @Test
    void evaluationIncludesPendingClarificationAndJournalTools() {
        var provider = mock(AssistantProvider.class);
        when(provider.converse(anyList(), anyList(), any())).thenReturn(new AssistantGeminiTurn.Result("""
                {"parts":[{"kind":"WRITE_CLARIFICATION","text":"Bạn muốn ghi vào lúc nào?","sources":[]}]}
                """, AssistantGeminiTurn.Grounding.OPEN));
        var answers = new AssistantConversationalAnswers(provider, null, new AssistantCourseAnswers(
                mock(SleepJournalService.class), mock(SleepRecentStatisticsService.class),
                mock(WaterService.class), Clock.systemUTC()));
        var previousQuestion = new org.springframework.ai.chat.messages.AssistantMessage("Bạn đã uống bao nhiêu?");
        var previousAnswer = new org.springframework.ai.chat.messages.UserMessage("250ml");

        var evaluation = answers.evaluate("synthetic-owner", "buổi sáng", UUID.randomUUID(), List.of(),
                List.of(previousQuestion, previousAnswer), true, () -> true, () -> true);

        assertThat(evaluation.action()).isNull();
        assertThat(evaluation.answer().outcome()).isEqualTo("WRITE_CLARIFICATION");
        var messages = ArgumentCaptor.forClass(List.class);
        var tools = ArgumentCaptor.forClass(List.class);
        verify(provider).converse(messages.capture(), tools.capture(), any());
        assertThat(messages.getValue()).containsSubsequence(previousQuestion, previousAnswer);
        assertThat(tools.getValue()).extracting(callback -> ((ToolCallback) callback).getToolDefinition().name())
                .contains("recordWater", "recordSleep");
    }

    @Test
    void providerFailureUsesSafeFallbackWithoutReadingData() {
        var provider = mock(AssistantProvider.class);
        var sleep = mock(SleepRecentStatisticsService.class);
        var water = mock(WaterService.class);
        var journal = mock(SleepJournalService.class);
        when(provider.converse(anyList(), anyList(), any())).thenThrow(new IllegalStateException("internal detail"));
        var answers = new AssistantConversationalAnswers(provider, null,
                new AssistantCourseAnswers(journal, sleep, water, Clock.systemUTC()));

        var reply = answers.answer("synthetic-owner", "Giấc ngủ gần đây của tôi", UUID.randomUUID(),
                List.of(), true, () -> true, () -> true);

        assertThat(reply.outcome()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(reply.parts().getFirst().text()).contains("thử lại sau", "cấp cứu")
                .doesNotContain("internal detail", "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(sleep, water, journal);
    }

    @Test
    void faqKeepsVerifiedAnswerWhenGeminiAddsUncitedConversation() {
        var provider = mock(AssistantProvider.class);
        var search = mock(AssistantFixtureSearch.class);
        var evidence = mock(AssistantFixtureSearch.ReadyEvidence.class);
        var faq = new AssistantCourseAnswers.Source("HEALTH-FIXTURE-001", 1, "Synthetic FAQ", "", null, null);
        when(evidence.evidence()).thenReturn(new AssistantProvider.FaqEvidence(
                faq.id(), "Câu hỏi mô phỏng?", "Nội dung mô phỏng có nguồn."));
        when(evidence.source()).thenReturn(faq);
        when(evidence.healthEducation()).thenReturn(true);
        when(evidence.current()).thenReturn(true);
        when(search.retrieveReadyEvidence(any(), any())).thenReturn(List.of(evidence));
        when(provider.converse(anyList(), anyList(), any())).thenAnswer(invocation -> {
            var callbacks = invocation.<List<ToolCallback>>getArgument(1);
            var tool = callbacks.stream()
                    .filter(callback -> callback.getToolDefinition().name().equals("searchApprovedKnowledge"))
                    .findFirst().orElseThrow();
            assertThat(tool.call("""
                    {"query":"Câu hỏi mô phỏng?","healthEducation":true}
                    """)).contains("FOUND", "e1");
            return new AssistantGeminiTurn.Result("""
                    {"parts":[{"kind":"GROUNDED","text":"Nội dung mô phỏng có nguồn.","sources":["e1"]},
                    {"kind":"CONVERSATION","text":"Chi tiết mô phỏng chưa xác minh.","sources":[]}]}
                    """, AssistantGeminiTurn.Grounding.APPROVED_KNOWLEDGE);
        });
        var answers = new AssistantConversationalAnswers(provider, search, new AssistantCourseAnswers(
                mock(SleepJournalService.class), mock(SleepRecentStatisticsService.class),
                mock(WaterService.class), Clock.systemUTC()));

        var reply = answers.answer("synthetic-owner", "Câu hỏi mô phỏng?", UUID.randomUUID(),
                List.of(), true, () -> true, () -> true);

        assertThat(reply.outcome()).isEqualTo("ANSWERED");
        assertThat(reply.parts()).containsExactly(new AssistantCourseAnswers.Part(
                "ANSWERED", "Nội dung mô phỏng có nguồn.", faq));
    }

    @Test
    void restrictedReplyWithOnlyConversationRemainsInvalid() {
        String json = """
                {"parts":[{"kind":"CONVERSATION","text":"Chi tiết mô phỏng chưa xác minh.","sources":[]}]}
                """;
        assertThatThrownBy(() -> AssistantConversationReply.parse(json, Map.of(), false))
                .isInstanceOf(AssistantConversationReply.InvalidReply.class);
    }

    @Test
    void discardsUncitedHealthGuidanceWhenNoApprovedEvidenceWasFound() {
        var provider = mock(AssistantProvider.class);
        when(provider.converse(anyList(), anyList(), any())).thenReturn(new AssistantGeminiTurn.Result("""
                {"parts":[{"kind":"CONVERSATION","text":"Bạn nên ngủ 9 tiếng mỗi đêm.","sources":[]}]}
                """, AssistantGeminiTurn.Grounding.APPROVED_KNOWLEDGE));
        var answers = new AssistantConversationalAnswers(provider, null, new AssistantCourseAnswers(
                mock(SleepJournalService.class), mock(SleepRecentStatisticsService.class),
                mock(WaterService.class), Clock.systemUTC()));

        var reply = answers.answer("synthetic-owner", "Nên ngủ bao lâu?", UUID.randomUUID(),
                List.of(), true, () -> true, () -> true);

        assertThat(reply.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(reply.parts().getFirst().text()).doesNotContain("9 tiếng");
    }
}
