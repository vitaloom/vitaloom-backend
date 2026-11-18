package com.vitaloom.backend.assistant;

import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentResponse;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantMultiFaqSearchTest {
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
    void sendsRankedEligibleEvidenceOnceWithRequestedTopKAndMapsOnlyReturnedIds() {
        candidates(5, row("source-b", .92), row("rejected", .91), row("source-a", .89));
        when(documents.first()).thenReturn(faq("source-b"), null, faq("source-a"), faq("source-b"), faq("source-a"));
        when(provider.answerFaqContext(eq(QUESTION), anyList())).thenReturn(List.of(
                new GeminiAssistantProvider.FaqPart("source-a", "Answer A"),
                new GeminiAssistantProvider.FaqPart("source-b", "Answer B")));

        var result = search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 5);

        assertThat(result.outcome()).isEqualTo("ANSWERED");
        assertThat(result.parts()).extracting(AssistantCourseAnswers.Part::text)
                .containsExactly("Answer A", "Answer B");
        assertThat(result.parts()).extracting(part -> part.source().id()).containsExactly("source-a", "source-b");
        var evidence = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider, times(1)).answerFaqContext(eq(QUESTION), evidence.capture());
        assertThat(evidence.getValue()).extracting(item -> ((GeminiAssistantProvider.FaqEvidence) item).id())
                .containsExactly("source-b", "source-a");
    }

    @Test
    void skipsRejectedMetadataAndDeduplicatesCanonicalAnswersBeforeCappingAtThree() {
        candidates(10, row("rejected", .97), row("one", .96), row("two", .95), row("duplicate", .94),
                row("fourth", .93));
        when(documents.first()).thenReturn(null, faq("one", "Café answer"), faq("two", "second"),
                faq("duplicate", "Cafe\u0301   ANSWER"), faq("fourth", "fourth"),
                faq("one", "Café answer"), faq("two", "second"));
        when(provider.answerFaqContext(eq(QUESTION), anyList())).thenReturn(List.of(
                new GeminiAssistantProvider.FaqPart("one", "one"),
                new GeminiAssistantProvider.FaqPart("two", "two")));

        search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 10);

        var evidence = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider).answerFaqContext(eq(QUESTION), evidence.capture());
        assertThat(evidence.getValue()).extracting(item -> ((GeminiAssistantProvider.FaqEvidence) item).id())
                .containsExactly("one", "two", "fourth");
    }

    @Test
    void doesNotDispatchWhenNoCandidateMeetsThreshold() {
        candidates(5, row("weak", .749));
        assertThat(search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 5).outcome())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(provider, never()).answerFaqContext(anyString(), anyList());
    }

    @Test
    void rejectsProviderCitationOutsideSelectedEvidence() {
        candidates(5, row("selected", .90));
        when(documents.first()).thenReturn(faq("selected"));
        when(provider.answerFaqContext(eq(QUESTION), anyList())).thenReturn(
                List.of(new GeminiAssistantProvider.FaqPart("invented", "answer")));
        assertThatThrownBy(() -> search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 5))
                .isInstanceOf(GeminiAssistantProvider.InvalidFaqResponse.class);
    }

    @Test
    void discardsWholeAnswerWhenAnyCitationIsRevokedDuringGeneration() {
        candidates(5, row("one", .92), row("two", .90));
        when(documents.first()).thenReturn(faq("one"), faq("two"), faq("one"), null);
        when(provider.answerFaqContext(eq(QUESTION), anyList())).thenReturn(List.of(
                new GeminiAssistantProvider.FaqPart("one", "one answer"),
                new GeminiAssistantProvider.FaqPart("two", "two answer")));
        var result = search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 5);
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.parts()).hasSize(1);
        assertThat(result.parts().getFirst().source()).isNull();
    }

    @Test
    void propagatesProviderFailureWithoutFallbackDispatch() {
        candidates(5, row("one", .92), row("two", .90));
        when(documents.first()).thenReturn(faq("one"));
        var failure = new IllegalStateException("provider failed");
        when(provider.answerFaqContext(eq(QUESTION), anyList())).thenThrow(failure);
        assertThatThrownBy(() -> search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 5)).isSameAs(failure);
        verify(provider, times(1)).answerFaqContext(eq(QUESTION), anyList());
    }

    @Test
    void rejectsUnsupportedTopK() {
        assertThatThrownBy(() -> search.answerReadyFaqContext(QUESTION, UUID.randomUUID(), 4))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider);
    }

    @SuppressWarnings("unchecked")
    private void candidates(int limit, Document... rows) {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        var iterator = List.of(rows).iterator();
        when(collection.aggregate(argThat(pipeline ->
                pipeline.getFirst() instanceof Document stage
                        && Integer.valueOf(limit).equals(
                                stage.get("$vectorSearch", Document.class).getInteger("limit")))))
                .thenReturn(aggregate);
        when(aggregate.maxTime(anyLong(), any())).thenReturn(aggregate);
        when(aggregate.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
        when(cursor.next()).thenAnswer(call -> iterator.next());
    }

    private static Document row(String id, double score) {
        return new Document("_id", id).append("score", score).append("version", 1).append("contentHash", "hash");
    }

    private static Document faq(String id) { return faq(id, id + " answer"); }

    private static Document faq(String id, String answer) {
        return row(id, 1).append("question", id + " question").append("answer", answer).append("title", id + " title");
    }
}
