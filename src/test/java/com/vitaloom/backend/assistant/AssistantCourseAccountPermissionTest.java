package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AssistantCourseAccountPermissionTest {
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantCourseAnswers answers = mock(AssistantCourseAnswers.class);
    private final AssistantFixtureSearch search = mock(AssistantFixtureSearch.class);
    private final AssistantAccountSettings settings = mock(AssistantAccountSettings.class);
    private final AssistantCourseController controller = new AssistantCourseController(mongo, answers,
            Clock.fixed(NOW, ZoneOffset.UTC), new StaticListableBeanFactory(
                    Map.of("assistantReadyFaqSearch", search)).getBeanProvider(AssistantFixtureSearch.class), settings);
    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("owner-a", "");
    private final UUID conversationId = UUID.randomUUID();
    private final AssistantAccountSettings.Preference denied =
            new AssistantAccountSettings.Preference("owner-a", false, 1);

    @Test
    void legacyConversationGrantDoesNotOverrideTheAccountSetting() {
        var legacy = new AssistantConversation(conversationId.toString(), "owner-a", 1L, NOW,
                NOW.plusSeconds(600), true, List.of());
        when(settings.get("owner-a")).thenReturn(denied);
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(legacy);

        var response = controller.get(authentication, conversationId);

        assertThat(response.personalAllowed()).isFalse();
        assertThat(response.turns()).isEmpty();
    }

    @Test
    void disabledAccountPermissionBlocksPersonalReadsOnLegacyConversations() {
        String question = "lịch sử giấc ngủ gần đây của tôi";
        var legacy = new AssistantConversation(conversationId.toString(), "owner-a", 1L, NOW,
                NOW.plusSeconds(600), true, List.of());
        when(settings.get("owner-a")).thenReturn(denied);
        when(settings.isUnchanged("owner-a", denied)).thenReturn(true);
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(legacy);
        when(answers.readyFaqLocalRoute(eq("owner-a"), eq(question), eq(false), eq(null),
                any(java.util.function.BooleanSupplier.class)))
                .thenReturn(new AssistantCourseAnswers.Answer("PERMISSION_REQUIRED", List.of(), null));
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));

        var response = controller.turn(authentication, conversationId,
                new AssistantCourseController.Send(UUID.randomUUID(), question, true));

        assertThat(response.personalAllowed()).isFalse();
        assertThat(response.turns().getFirst().outcome()).isEqualTo("PERMISSION_REQUIRED");
        verifyNoPersonalRead();
    }

    @Test
    void legacyPermissionUpdateNeverDeletesHistoryAndRequiresAccountSettingsForChanges() {
        var savedTurn = new AssistantConversation.Turn(UUID.randomUUID().toString(), "hello", NOW,
                new AssistantCourseAnswers.Answer("ANSWERED", List.of(), null));
        var existing = new AssistantConversation(conversationId.toString(), "owner-a", 2L, NOW,
                NOW.plusSeconds(600), true, List.of(savedTurn));
        when(settings.get("owner-a")).thenReturn(denied);
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(existing);

        var matching = controller.permission(authentication, conversationId,
                new AssistantCourseController.Permission(false));

        assertThat(matching.personalAllowed()).isFalse();
        assertThat(matching.turns()).hasSize(1);
        assertThatThrownBy(() -> controller.permission(authentication, conversationId,
                new AssistantCourseController.Permission(true)))
                .isInstanceOf(AssistantAccountSettingsController.AssistantAccountSettingRequiredException.class);
        verify(mongo, never()).save(any(AssistantConversation.class));
    }

    @Test
    void changingSettingsWhileAQuestionIsProcessingPreventsPublishingItsAnswer() {
        var enabled = new AssistantAccountSettings.Preference("owner-a", true, 1);
        var existing = new AssistantConversation(conversationId.toString(), "owner-a", 2L, NOW,
                NOW.plusSeconds(600), false, List.of());
        String question = "lịch sử giấc ngủ gần đây của tôi";
        when(settings.get("owner-a")).thenReturn(enabled);
        when(settings.isCurrent("owner-a", enabled)).thenReturn(true, false);
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(existing);
        when(answers.readyFaqLocalRoute(eq("owner-a"), eq(question), eq(true), eq(null), any()))
                .thenReturn(null);
        when(answers.answer(eq("owner-a"), eq(question), eq(true), eq(null), any()))
                .thenAnswer(call -> {
                    var authorized = (java.util.function.BooleanSupplier) call.getArgument(4);
                    assertThat(authorized.getAsBoolean()).isTrue();
                    return new AssistantCourseAnswers.Answer("ANSWERED", List.of(), null);
                });

        assertThatThrownBy(() -> controller.turn(authentication, conversationId,
                new AssistantCourseController.Send(UUID.randomUUID(), question, false)))
                .isInstanceOf(AssistantCourseController.AccountSettingChangedException.class);
        verify(mongo, never()).save(any(AssistantConversation.class));
    }

    private void verifyNoPersonalRead() {
        verify(answers).readyFaqLocalRoute(eq("owner-a"), eq("lịch sử giấc ngủ gần đây của tôi"),
                eq(false), eq(null), any(java.util.function.BooleanSupplier.class));
        verify(answers, never()).personalTools(any(), any(), any());
    }
}
