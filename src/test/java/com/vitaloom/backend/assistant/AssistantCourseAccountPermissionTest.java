package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AssistantCourseAccountPermissionTest {
    private static final String OWNER = "synthetic-owner";
    private static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
    private final UUID conversationId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantAccountSettings settings = mock(AssistantAccountSettings.class);
    private final AssistantProvider model = mock(AssistantProvider.class);
    private final Authentication auth = mock(Authentication.class);
    private final AssistantAccountSettings.Preference grant = new AssistantAccountSettings.Preference(OWNER, true, 3, AssistantAccountSettings.CONSENT_VERSION);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AssistantCourseController controller = controller(true);

    @BeforeEach
    void setUp() {
        when(model.checkPolicy(anyList(), any())).thenReturn(AssistantScriptedChat.ALLOW);
        when(auth.getName()).thenReturn(OWNER);
        when(settings.requireEnabled(OWNER)).thenAnswer(call -> {
            if (!enabled.get()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return grant;
        });
        when(settings.isCurrent(OWNER, grant)).thenAnswer(call -> enabled.get());
    }

    @Test
    void disabledPermissionRejectsAllConversationOperationsBeforeAccessingData() {
        enabled.set(false);
        assertForbidden(() -> controller.create(auth, null));
        assertForbidden(() -> controller.list(auth, 0));
        assertForbidden(() -> controller.get(auth, conversationId));
        assertForbidden(() -> controller.delete(auth, conversationId));
        assertForbidden(() -> controller.permission(auth, conversationId, new AssistantCourseController.Permission(true)));
        assertForbidden(() -> controller.turn(auth, conversationId,
                new AssistantCourseController.Send(UUID.randomUUID(), "Lịch sử ngủ", null)));
        verifyNoInteractions(mongo, model);
    }

    @Test
    void disabledPermissionReturnsForbiddenOnDirectHttpAccess() throws Exception {
        enabled.set(false);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/assistant/mvp/conversations").principal(auth))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mongo, model);
    }

    @Test
    void currentGrantUsesGeminiEvenWithoutTheOldPerTurnFlag() {
        var turnId = UUID.randomUUID();
        var priorSource = new AssistantCourseAnswers.Source("sleep-recent", 1, "Giấc ngủ", "/sleep/recent", null, null);
        var previous = new AssistantCourseAnswers.Answer("ANSWERED",
                List.of(new AssistantCourseAnswers.Part("ANSWERED", "Previous response", priorSource)), null);
        var old = new AssistantCourseAnswers.Answer("ANSWERED", List.of(new AssistantCourseAnswers.Part("ANSWERED",
                "Old reply", new AssistantCourseAnswers.Source("water-day", 1, "Water", "/water/progress",
                "2026-11-18", "2026-11-18"))), null);
        stubConversation(List.of(
                new AssistantConversation.Turn(UUID.randomUUID().toString(), "Old grant",
                        NOW.minus(Duration.ofMinutes(2)), old, null, 2L),
                new AssistantConversation.Turn(UUID.randomUUID().toString(), "Previous raw prompt",
                        NOW.minus(Duration.ofMinutes(1)), previous, null, 3L)));
        when(mongo.indexOps(AssistantConversationTurns.Receipt.class)).thenReturn(mock(IndexOperations.class));
        when(mongo.updateFirst(any(Query.class), any(), eq(AssistantConversationTurns.Receipt.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(call -> {
            List<org.springframework.ai.chat.messages.Message> messages = call.getArgument(0);
            assertThat(messages.getFirst().getText()).contains("Main Sleep duration in the rolling seven-day window")
                    .doesNotContain(OWNER, "Previous raw prompt", "Previous response", "Old reply",
                            "Water saved in the journal");
            assertThat(messages.getLast().getText()).isEqualTo("Cảm ơn");
            return "Mình luôn sẵn sàng hỗ trợ bạn.";
        });

        var response = controller.turn(auth, conversationId, new AssistantCourseController.Send(turnId, "Cảm ơn", false));
        assertThat(response.turns().getLast().parts().getFirst().text()).startsWith("Mình luôn");
        assertThat(response.personalAllowed()).isTrue();
        verify(mongo).save(any(AssistantConversation.class));
    }

    @Test
    void revocationDuringProviderWorkPreventsPublicationAndPersistence() {
        stubConversation(List.of());
        when(model.converse(anyList(), anyList(), any(), any())).thenAnswer(call -> {
            BooleanSupplier current = call.getArgument(2);
            assertThat(current.getAsBoolean()).isTrue();
            enabled.set(false);
            assertThat(current.getAsBoolean()).isFalse();
            return "";
        });

        assertThatThrownBy(() -> controller.turn(auth, conversationId,
                new AssistantCourseController.Send(UUID.randomUUID(), "Thông tin của tôi", null)))
                .isInstanceOf(AssistantCourseController.AccountSettingChangedException.class);
        verify(mongo, never()).save(any(AssistantConversation.class));
    }

    @Test
    void providerConfigurationFailureDoesNotChangeConsentOrDispatch() {
        stubConversation(List.of());
        var blocked = controller(false);
        assertThatThrownBy(() -> blocked.turn(auth, conversationId,
                new AssistantCourseController.Send(UUID.randomUUID(), "Xin chào", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(enabled.get()).isTrue();
        verifyNoInteractions(model);
    }

    @Test
    void replayIsReturnedWithoutANewProviderCallAfterRecheckingPermission() {
        var turnId = UUID.randomUUID();
        var saved = new AssistantConversation.Turn(turnId.toString(), "Xin chào", NOW,
                new AssistantCourseAnswers.Answer("ANSWERED", List.of(), null), null, null);
        stubConversation(List.of(saved));
        assertThat(controller.turn(auth, conversationId,
                new AssistantCourseController.Send(turnId, "Xin chào", null)).turns()).hasSize(1);
        verify(settings, org.mockito.Mockito.atLeastOnce()).isCurrent(OWNER, grant);
        verifyNoInteractions(model);
    }

    private void stubConversation(List<AssistantConversation.Turn> turns) {
        var row = new AssistantConversation(conversationId.toString(), OWNER, 7L, NOW, NOW.plusSeconds(3600),
                true, turns);
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.exists(any(Query.class), eq(AssistantConversation.class))).thenReturn(true);
    }

    private AssistantCourseAnswers answers() {
        return new AssistantCourseAnswers(mock(SleepJournalService.class), mock(SleepRecentStatisticsService.class),
                mock(WaterService.class), clock);
    }

    private AssistantCourseController controller(boolean ready) {
        var service = new AssistantConversationTurns(mongo, clock, answers(), providerFor(null), providerFor(model),
                new AssistantGeminiReadiness(ready), settings, mock(WaterService.class), mock(SleepJournalService.class));
        return new AssistantCourseController(mongo, clock, settings, service);
    }

    private static <T> ObjectProvider<T> providerFor(T available) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return available;
            }
        };
    }

    private static void assertForbidden(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
