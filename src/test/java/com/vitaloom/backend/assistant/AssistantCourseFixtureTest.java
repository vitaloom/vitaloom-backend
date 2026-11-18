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
        when(local.answer("synthetic-b", "Synthetic FAQ question", false, null))
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
        verify(local).readyFaqLocalRoute("synthetic-b", AssistantFixture.QUESTION, false, null);
        verifyNoMoreInteractions(local);
    }

    @Test
    void stockPriceQueryUsesLocalRouteBeforeReadyFaqDispatch() {
        var row = new AssistantConversation(conversation.toString(), "synthetic-b", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(local.readyFaqLocalRoute("synthetic-b", "What is the stock price?", false, null)).thenReturn(
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

    private void assertLocal(String owner, String text, Boolean accepted) {
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(
                new AssistantConversation(conversation.toString(), owner, 0L, NOW,
                        NOW.plusSeconds(600), false, List.of()));
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(local.answer(owner, text, false, null))
                .thenReturn(new AssistantCourseAnswers.Answer("LOCAL", List.of(), null));

        var reply = controller.turn(new TestingAuthenticationToken(owner, ""), conversation,
                new AssistantCourseController.Send(turn, text, accepted));
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("LOCAL");
    }
}
