package com.vitaloom.backend.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantCourseFixtureTest {
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantCourseAnswers local = mock(AssistantCourseAnswers.class);
    private final AssistantFixtureSearch search = mock(AssistantFixtureSearch.class);
    private final AssistantCourseController controller = new AssistantCourseController(mongo, local,
            Clock.fixed(NOW, ZoneOffset.UTC), new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    java.util.Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class));
    private final UUID conversation = UUID.randomUUID();
    private final UUID turn = UUID.randomUUID();

    @Test
    void withoutConsentOrSearchUsesLocalAnswersForAuthenticatedOwners() {
        assertLocal("synthetic-a", AssistantFixture.QUESTION, null);
        assertLocal("synthetic-b", AssistantFixture.QUESTION, false);
        assertLocal("synthetic-a", AssistantFixture.QUESTION + " 375 ml", false);
        verifyNoInteractions(search);
    }

    @Test
    void readyFaqUsesAnyAuthenticatedOwnerAndPerTurnConsentGate() {
        var declinedRow = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(declinedRow);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(local.answer(eq("synthetic-b"), eq("Synthetic FAQ question"), eq(false), eq(null),
                any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new AssistantCourseAnswers.Answer("LOCAL", List.of(), null));
        var declined = controller.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(turn, "Synthetic FAQ question", false));
        assertThat(declined.turns().getFirst().outcome()).isEqualTo("LOCAL");
        verifyNoInteractions(search);

        String question = "Synthetic FAQ question";
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(search.answerReadyFaqContext(question, turn, 10)).thenReturn(
                new AssistantCourseAnswers.Answer("ANSWERED", List.of(), null));
        var reply = controller.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(turn, question, true));
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("ANSWERED");
        verify(search).answerReadyFaqContext(question, turn, 10);
        verifyNoMoreInteractions(search);
    }

    @Test
    void returnsReadyCitationAndDoesNotDispatchOnSavedReplay() {
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(search.answerReadyFaqContext(AssistantFixture.QUESTION, turn, 10)).thenReturn(
                new AssistantCourseAnswers.Answer("ANSWERED", List.of(new AssistantCourseAnswers.Part("ANSWERED",
                        "Mở mục Giấc ngủ rồi chọn Nhật ký.", AssistantFixtureSearch.SOURCE)), null));

        var request = new AssistantCourseController.Send(turn, AssistantFixture.QUESTION, true);
        var auth = new TestingAuthenticationToken("synthetic-b", "");
        var reply = controller.turn(auth, conversation, request);
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("ANSWERED");
        assertThat(reply.turns().getFirst().parts().getFirst().source()).isEqualTo(AssistantFixtureSearch.SOURCE);
        var saved = row.change(NOW, false, List.of(new AssistantConversation.Turn(turn.toString(),
                AssistantFixture.QUESTION, NOW, new AssistantCourseAnswers.Answer("ANSWERED",
                        reply.turns().getFirst().parts(), null))));
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(saved);
        assertThat(controller.turn(auth, conversation, request)).isEqualTo(reply);
        verify(search, times(1)).answerReadyFaqContext(AssistantFixture.QUESTION, turn, 10);
        verify(local).readyFaqLocalRoute(eq("synthetic-b"), eq(AssistantFixture.QUESTION), eq(false), eq(null),
                any(java.util.function.BooleanSupplier.class));
        verifyNoMoreInteractions(local);
    }

    @Test
    void stockPriceQueryUsesLocalRouteBeforeReadyFaqDispatch() {
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(local.readyFaqLocalRoute(eq("synthetic-b"), eq("What is the stock price?"), eq(false), eq(null),
                any(java.util.function.BooleanSupplier.class))).thenReturn(
                new AssistantCourseAnswers.Answer("INSUFFICIENT_EVIDENCE", List.of(), null));
        var reply = controller.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(turn, "What is the stock price?", true));
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(search);
    }

    @Test
    void prescriptionStockAndUrgentSymptomsAreRoutedLocally() {
        var answers = new AssistantCourseAnswers(mock(SleepJournalService.class),
                mock(SleepRecentStatisticsService.class), mock(WaterService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(answers.readyFaqLocalRoute("synthetic", "Tôi nên uống thuốc gì?", false, null).outcome()).isEqualTo("SAFETY");
        assertThat(answers.readyFaqLocalRoute("synthetic", "Giá cổ phiếu Apple hôm nay?", false, null).outcome())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(answers.readyFaqLocalRoute("synthetic", "Tôi bị đau ngực", false, null).outcome()).isEqualTo("SAFETY");
    }

    @Test
    void readyFaqRoutesPersonalRecentSleepToOwnerStatistics() {
        var sleep = mock(SleepRecentStatisticsService.class);
        when(sleep.recent("synthetic-b")).thenReturn(new SleepRecentStatisticsService.Report(
                new SleepRecentStatisticsService.Period(java.time.LocalDate.parse("2026-11-11"),
                        java.time.LocalDate.parse("2026-11-17"), "Asia/Ho_Chi_Minh"),
                "VERIFIED", List.of(
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-11"), "RECORDED", 420),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-12"), "NOT_RECORDED", null),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-13"), "RECORDED", 390),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-14"), "RECORDED", 400),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-15"), "RECORDED", 410),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-16"), "RECORDED", 430),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-17"), "RECORDED", 440)),
                6, 7, 415, List.of(), List.of()));
        var answers = new AssistantCourseAnswers(mock(SleepJournalService.class), sleep,
                mock(WaterService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var readyController = new AssistantCourseController(mongo, answers, Clock.fixed(NOW, ZoneOffset.UTC),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class));
        when(mongo.findById("synthetic-b", AssistantAccountSettings.Preference.class))
                .thenReturn(new AssistantAccountSettings.Preference("synthetic-b", true, 1));
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class))).thenReturn(true);
        for (String question : List.of("lịch sử giấc ngủ gần đây của tôi",
                "gần đây giấc ngủ của tôi diễn biến như thế nào")) {
            var id = UUID.randomUUID();
            var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                    NOW.plusSeconds(600), true, List.of());
            when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
            when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
            var reply = readyController.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                    new AssistantCourseController.Send(id, question, true));
            assertThat(reply.turns().getLast().outcome()).isEqualTo("ANSWERED");
            var part = reply.turns().getLast().parts().getFirst();
            assertThat(part.source().id()).isEqualTo("sleep-recent");
            assertThat(part.source().route()).isEqualTo("/sleep/recent");
            assertThat(part.source().from()).isEqualTo("2026-11-11");
            assertThat(part.source().to()).isEqualTo("2026-11-17");
            assertThat(part.text()).contains("2026-11-11: 420 phút", "2026-11-12: Chưa ghi nhận",
                    "2026-11-17: 440 phút", "trung bình 415 phút/ngày");
        }
        verify(sleep, times(2)).recent("synthetic-b");
        verifyNoInteractions(search);
    }

    @Test
    void recentSleepRequiresPermissionAndGenericRecentFaqStillUsesSearch() {
        var sleep = mock(SleepRecentStatisticsService.class);
        var answers = new AssistantCourseAnswers(mock(SleepJournalService.class), sleep,
                mock(WaterService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var readyController = new AssistantCourseController(mongo, answers, Clock.fixed(NOW, ZoneOffset.UTC),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class));
        var disabled = new AssistantAccountSettings.Preference("synthetic-b", false, 0);
        var enabled = new AssistantAccountSettings.Preference("synthetic-b", true, 1);
        when(mongo.findById("synthetic-b", AssistantAccountSettings.Preference.class))
                .thenReturn(disabled, disabled, enabled, enabled);
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        var denied = readyController.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(UUID.randomUUID(), "lịch sử giấc ngủ gần đây của tôi", true));
        assertThat(denied.turns().getLast().outcome()).isEqualTo("PERMISSION_REQUIRED");
        verifyNoInteractions(sleep, search);

        var permittedRow = row.change(NOW, true, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(permittedRow);
        when(search.answerReadyFaqContext("cách tính thời lượng trung bình giấc ngủ gần đây", turn, 10))
                .thenReturn(new AssistantCourseAnswers.Answer("ANSWERED", List.of(), null));
        var faq = readyController.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(turn, "cách tính thời lượng trung bình giấc ngủ gần đây", true));
        assertThat(faq.turns().getLast().outcome()).isEqualTo("ANSWERED");
        verify(search).answerReadyFaqContext("cách tính thời lượng trung bình giấc ngủ gần đây", turn, 10);
        verifyNoInteractions(sleep);
    }

    @Test
    void readyFaqReportsNoDataForVerifiedEmptyRecentSleep() {
        var sleep = mock(SleepRecentStatisticsService.class);
        when(sleep.recent("synthetic-b")).thenReturn(new SleepRecentStatisticsService.Report(
                new SleepRecentStatisticsService.Period(java.time.LocalDate.parse("2026-11-11"),
                        java.time.LocalDate.parse("2026-11-17"), "Asia/Ho_Chi_Minh"),
                "NO_DATA", List.of(), 0, 7, null, List.of(), List.of()));
        var answers = new AssistantCourseAnswers(mock(SleepJournalService.class), sleep,
                mock(WaterService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var readyController = new AssistantCourseController(mongo, answers, Clock.fixed(NOW, ZoneOffset.UTC),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class));
        when(mongo.findById("synthetic-b", AssistantAccountSettings.Preference.class))
                .thenReturn(new AssistantAccountSettings.Preference("synthetic-b", true, 1));
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class))).thenReturn(true);
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), true, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        var reply = readyController.turn(new TestingAuthenticationToken("synthetic-b", ""), conversation,
                new AssistantCourseController.Send(turn, "giấc ngủ gần đây?", true));
        assertThat(reply.turns().getLast().outcome()).isEqualTo("NO_DATA");
        assertThat(reply.turns().getLast().parts().getFirst().source().route()).isEqualTo("/sleep/recent");
        assertThat(reply.turns().getLast().parts().getFirst().text()).isEqualTo("Chưa có bản ghi cho khoảng thời gian này.");
        verify(sleep).recent("synthetic-b");
        verifyNoInteractions(search);
    }

    @Test
    void followsSleepTopicAndAnalysisThroughTimeChangeAndCorrectionWithoutCrossRouting() {
        var journal = mock(SleepJournalService.class);
        when(journal.list("synthetic-b", "2026-11-17", "2026-11-17")).thenReturn(List.of(
                new com.vitaloom.backend.sleep.dto.response.SessionResponse("sample", NOW, "MAIN",
                        "DURATION", null, "2026-11-17", 360, null, null, null, null, null, false)));
        var sleep = mock(SleepRecentStatisticsService.class);
        when(sleep.recent("synthetic-b")).thenReturn(new SleepRecentStatisticsService.Report(
                new SleepRecentStatisticsService.Period(java.time.LocalDate.parse("2026-11-11"),
                        java.time.LocalDate.parse("2026-11-17"), "Asia/Ho_Chi_Minh"),
                "VERIFIED", List.of(
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-11"), "RECORDED", 360),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-12"), "NOT_RECORDED", null),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-13"), "RECORDED", 390),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-14"), "RECORDED", 400),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-15"), "RECORDED", 410),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-16"), "RECORDED", 430),
                        new SleepRecentStatisticsService.Day(java.time.LocalDate.parse("2026-11-17"), "RECORDED", 440)),
                6, 7, 405, List.of(), List.of()));
        var water = mock(WaterService.class);
        var answers = new AssistantCourseAnswers(journal, sleep,
                water, Clock.fixed(NOW, ZoneOffset.UTC));
        var readyController = new AssistantCourseController(mongo, answers, Clock.fixed(NOW, ZoneOffset.UTC),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class));
        var auth = new TestingAuthenticationToken("synthetic-b", "");
        var history = new java.util.ArrayList<AssistantConversation.Turn>();
        when(mongo.findById("synthetic-b", AssistantAccountSettings.Preference.class)).thenReturn(
                new AssistantAccountSettings.Preference("synthetic-b", true, 1));
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class))).thenReturn(true);
        var prompts = List.of("Hôm nay tôi chỉ ngủ 6 tiếng, có tốt cho sức khoẻ không?",
                "Thời lượng ngủ hôm nay của tôi nói lên điều gì?",
                "Có bất thường gì trong thời lượng ngủ của tôi không?",
                "Trong tuần này",
                "Trong tuần này có bất thường không?",
                "Tôi đang nói về ngủ, không phải uống nước",
                "Giấc ngủ lẫn lượng nước tuần này có bất thường không?",
                "Trong tuần này");
        when(search.answerReadySleepGuidance(any(UUID.class))).thenReturn(
                new AssistantCourseAnswers.Answer("ANSWERED", List.of(new AssistantCourseAnswers.Part(
                        "ANSWERED", "Theo CDC, người lớn 18–60 tuổi thường được khuyến nghị ngủ ít nhất 7 giờ.",
                        new AssistantCourseAnswers.Source(
                                "READY-MANUAL-001", 1, "Ngủ bao lâu", "", null, null))), null));
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> {
            var persisted = call.<AssistantConversation>getArgument(0);
            history.add(persisted.turns().getLast());
            return persisted;
        });
        for (var prompt : prompts) {
            var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                    NOW.plusSeconds(600), true, List.copyOf(history));
            when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
            readyController.turn(auth, conversation,
                    new AssistantCourseController.Send(UUID.randomUUID(), prompt, true));
        }
        assertThat(history.getFirst().reply().context().intent()).isEqualTo(AssistantCourseAnswers.Intent.SLEEP_DAY);
        assertThat(history.getFirst().reply().parts()).extracting(part -> part.source().id())
                .containsExactly("sleep-day", "READY-MANUAL-001");
        assertThat(history.get(1).reply().parts().getFirst().text()).contains("360", "chất lượng");
        assertThat(history.get(2).reply().parts().getFirst().text()).contains("360", "bất thường");
        assertThat(history.get(3).reply().context().intent()).isEqualTo(AssistantCourseAnswers.Intent.SLEEP_RECENT);
        assertThat(history.get(3).reply().context().window()).isEqualTo(AssistantCourseAnswers.Window.WEEK_TO_DATE);
        assertThat(history.get(4).reply().parts().getFirst().source().id()).isEqualTo("sleep-week-to-date");
        assertThat(history.get(4).reply().parts().getFirst().text()).contains("2026-11-16", "2026-11-17")
                .doesNotContain("2026-11-11");
        assertThat(history.get(5).reply().context().intent()).isEqualTo(AssistantCourseAnswers.Intent.SLEEP_RECENT);
        assertThat(history.get(5).reply().context().window()).isEqualTo(AssistantCourseAnswers.Window.WEEK_TO_DATE);
        assertThat(history.get(5).reply().context().analysis()).isEqualTo(AssistantCourseAnswers.Analysis.ANOMALY);
        assertThat(history.get(5).reply().parts().getFirst().source().id()).isEqualTo("sleep-week-to-date");
        assertThat(history.get(6).reply().outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(history.get(7).reply().parts().getFirst().source()).isNull();
        verify(sleep, times(3)).recent("synthetic-b");
        verify(search).answerReadySleepGuidance(any(UUID.class));
        verifyNoMoreInteractions(search);
        verifyNoInteractions(water);
    }

    @Test
    void retainsContextWhenTheAuthorizedPersonalReadIsUnavailable() {
        var journal = mock(SleepJournalService.class);
        when(journal.list("synthetic-b", "2026-11-17", "2026-11-17"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic read failure"));
        var answers = new AssistantCourseAnswers(journal, mock(SleepRecentStatisticsService.class),
                mock(WaterService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var unavailable = answers.readyFaqLocalRoute("synthetic-b",
                "Thời lượng ngủ hôm nay của tôi nói lên điều gì?", true, null);
        assertThat(unavailable.outcome()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(unavailable.context().analysis()).isEqualTo(AssistantCourseAnswers.Analysis.MEANING);
        assertThat(unavailable.parts().getFirst().source()).isNull();
        assertThat(answers.readyFaqLocalRoute("synthetic-b", "Nên uống bao nhiêu nước mỗi ngày?", true,
                unavailable.context())).isNull();
        assertThat(answers.readyFaqLocalRoute("synthetic-b", "Tôi nên ngủ bao nhiêu tiếng?", true,
                unavailable.context())).isNull();
        assertThat(answers.readyFaqLocalRoute("synthetic-b", "Should I sleep 8 hours?", true,
                unavailable.context())).isNull();
    }

    @Test
    void generalSleepDurationGuidanceDoesNotRequestAccountData() {
        var journal = mock(SleepJournalService.class);
        var sleep = mock(SleepRecentStatisticsService.class);
        var water = mock(WaterService.class);
        var answers = new AssistantCourseAnswers(journal, sleep, water, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(answers.readyFaqLocalRoute("synthetic-b", "Should I sleep 8 hours?", false, null)).isNull();
        assertThat(answers.readyFaqLocalRoute("synthetic-b", "Tôi nên ngủ bao nhiêu tiếng?", false, null)).isNull();
        verifyNoInteractions(journal, sleep, water);
    }

    private void assertLocal(String owner, String text, Boolean accepted) {
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(
                new AssistantConversation(conversation.toString(), owner, 0L, NOW,
                        NOW.plusSeconds(600), false, List.of()));
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(local.answer(eq(owner), eq(text), eq(false), eq(null), any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new AssistantCourseAnswers.Answer("LOCAL", List.of(), null));

        var reply = controller.turn(new TestingAuthenticationToken(owner, ""), conversation,
                new AssistantCourseController.Send(turn, text, accepted));
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("LOCAL");
    }
}
