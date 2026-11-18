package com.vitaloom.backend.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
    private final AssistantChatService claims = mock(AssistantChatService.class);
    private final AssistantCourseController controller = new AssistantCourseController(mongo, local,
            Clock.fixed(NOW, ZoneOffset.UTC), "", new ObjectProvider<>() {
                @Override public AssistantFixtureSearch getIfAvailable() { return search; }
            }, new ObjectProvider<>() {
                @Override public AssistantChatService getObject() { return claims; }
            }, "synthetic-a");
    private final UUID conversation = UUID.randomUUID();
    private final UUID turn = UUID.randomUUID();

    @Test
    void requiresExactFixtureExplicitAcknowledgementAndConfiguredOwner() {
        assertLocal("synthetic-a", AssistantFixture.QUESTION, null);
        assertLocal("synthetic-b", AssistantFixture.QUESTION, true);
        assertLocal("synthetic-a", AssistantFixture.QUESTION + " 375 ml", true);
        verifyNoInteractions(search, claims);
    }

    @Test
    void returnsServerCitationAndDoesNotDispatchOnSavedReplay() {
        var row = new AssistantConversation(conversation.toString(), "synthetic-a", 0L, NOW,
                NOW.plusSeconds(600), false, List.of());
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(row);
        when(mongo.save(any(AssistantConversation.class))).thenAnswer(call -> call.getArgument(0));
        when(search.answer(eq(AssistantFixture.JOURNAL), eq(AssistantFixture.QUESTION),
                any(BooleanSupplier.class))).thenAnswer(call -> call.<BooleanSupplier>getArgument(2).getAsBoolean());
        when(claims.answer(eq("synthetic-a:" + conversation), eq(turn), eq(AssistantFixture.QUESTION), any(BooleanSupplier.class)))
                .thenAnswer(call -> call.<BooleanSupplier>getArgument(3).getAsBoolean()
                        ? AssistantTurn.Status.ANSWERED : AssistantTurn.Status.DEPENDENCY_UNAVAILABLE);

        var request = new AssistantCourseController.Send(turn, AssistantFixture.QUESTION, true);
        var auth = new TestingAuthenticationToken("synthetic-a", "");
        var reply = controller.turn(auth, conversation, request);
        assertThat(reply.turns().getFirst().outcome()).isEqualTo("ANSWERED");
        assertThat(reply.turns().getFirst().parts().getFirst().source()).isEqualTo(AssistantFixtureSearch.SOURCE);
        var saved = row.change(NOW, false, List.of(new AssistantConversation.Turn(turn.toString(),
                AssistantFixture.QUESTION, NOW, new AssistantCourseAnswers.Answer(
                        "ANSWERED", reply.turns().getFirst().parts(), null))));
        when(mongo.findOne(any(Query.class), eq(AssistantConversation.class))).thenReturn(saved);
        assertThat(controller.turn(auth, conversation, request)).isEqualTo(reply);
        verify(search, times(1)).answer(eq(AssistantFixture.JOURNAL), eq(AssistantFixture.QUESTION), any());
        verifyNoInteractions(local);
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
