package com.vitaloom.backend.assistant;

import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentResponse;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantReadyFaqSearchTest {
    private static final String QUESTION = "Synthetic FAQ query";
    private final GeminiAssistantProvider provider = mock(GeminiAssistantProvider.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final FindIterable<Document> documents = mock(FindIterable.class);
    private final AssistantFixtureSearch search = new AssistantFixtureSearch(mongo, provider);

    @BeforeEach
    void setup() {
        when(mongo.getCollection("assistant_ready_faq")).thenReturn(collection);
        when(provider.embed(eq("gemini-embedding-2"), anyString(), any())).thenReturn(
                EmbedContentResponse.builder().embeddings(List.of(ContentEmbedding.builder()
                        .values(Collections.nCopies(768, 1f)).build())).build());
        when(collection.find(any(Bson.class))).thenReturn(documents);
    }

    @Test
    void triesSecondCandidateAfterFirstNoMatch() {
        candidates(row("first", 0.90), row("second", 0.85));
        when(documents.first()).thenReturn(faq("first"), faq("second"), faq("second"));
        when(provider.answerFaq(QUESTION, "second", "evidence")).thenReturn("Grounded answer");
        var result = search.answerReadyFaq(QUESTION, UUID.randomUUID());
        assertThat(result.outcome()).isEqualTo("ANSWERED");
        assertThat(result.parts().getFirst().source().id()).isEqualTo("second");
    }

    @Test
    void exhaustsEligibleCandidatesBeforeFallback() {
        candidates(row("first", 0.90), row("second", 0.85), row("third", 0.75));
        when(documents.first()).thenReturn(faq("first"), faq("second"), faq("third"));
        assertThat(search.answerReadyFaq(QUESTION, UUID.randomUUID()).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(provider, times(3)).answerFaq(eq(QUESTION), anyString(), eq("evidence"));
    }

    @Test
    void skipsBelowThreshold() {
        candidates(row("weak", 0.74));
        assertThat(search.answerReadyFaq(QUESTION, UUID.randomUUID()).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(provider, never()).answerFaq(anyString(), anyString(), anyString());
    }

    @Test
    void skipsRecordsThatFailPrimaryReleaseCheck() {
        candidates(row("draft", 0.91), row("blocked", 0.90));
        assertThat(search.answerReadyFaq(QUESTION, UUID.randomUUID()).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(collection, times(2)).find(argThat((Bson filter) -> filter instanceof Document query
                && "APPROVED".equals(query.getString("status")) && "READY".equals(query.getString("releaseGate"))));
        verify(provider, never()).answerFaq(anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackWithoutGenerationWhenSearchIsEmpty() {
        candidates();
        assertThat(search.answerReadyFaq(QUESTION, UUID.randomUUID()).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(provider, never()).answerFaq(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @MethodSource("failures")
    void propagatesProviderFailureWithoutTryingAnotherCandidate(RuntimeException failure) {
        candidates(row("first", 0.90), row("second", 0.85));
        when(documents.first()).thenReturn(faq("first"));
        when(provider.answerFaq(QUESTION, "first", "evidence")).thenThrow(failure);
        assertThatThrownBy(() -> search.answerReadyFaq(QUESTION, UUID.randomUUID())).isSameAs(failure);
        verify(provider, times(1)).answerFaq(anyString(), anyString(), anyString());
    }

    static Stream<RuntimeException> failures() {
        return Stream.of(new IllegalStateException("API error"),
                new IllegalStateException(new SocketTimeoutException("Timeout")),
                new GeminiAssistantProvider.InvalidFaqResponse());
    }

    @Test
    void parsesOnlyExplicitModelDecisions() {
        assertThat(GeminiAssistantProvider.parseFaqReply("{\"decision\":\"NO_MATCH\"}")).isNull();
        assertThat(GeminiAssistantProvider.parseFaqReply("{\"decision\":\"MATCH\",\"answer\":\"Grounded\"}"))
                .isEqualTo("Grounded");
        for (String invalid : List.of("", "unstructured prose", "{\"answer\":\"missing decision\"}",
                "{\"decision\":\"MATCH\",\"answer\":false}", "{decision:'NO_MATCH'}",
                "{\"decision\":\"NO_MATCH\"} trailing prose", "{\"decision\":\"NO_MATCH\"} {}",
                "{\"decision\":\"MATCH\",\"decision\":\"NO_MATCH\"}")) {
            assertThatThrownBy(() -> GeminiAssistantProvider.parseFaqReply(invalid), "invalid reply %s", invalid)
                    .isInstanceOf(GeminiAssistantProvider.InvalidFaqResponse.class);
        }
    }

    @SuppressWarnings("unchecked")
    private void candidates(Document... rows) {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        var iterator = List.of(rows).iterator();
        when(collection.aggregate(anyList())).thenReturn(aggregate);
        when(aggregate.maxTime(anyLong(), any())).thenReturn(aggregate);
        when(aggregate.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
        when(cursor.next()).thenAnswer(call -> iterator.next());
    }

    private static Document row(String id, double score) {
        return new Document("_id", id).append("score", score).append("version", 1).append("contentHash", "hash");
    }

    private static Document faq(String id) {
        return row(id, 1.0).append("question", id).append("answer", "evidence").append("title", id);
    }
}
