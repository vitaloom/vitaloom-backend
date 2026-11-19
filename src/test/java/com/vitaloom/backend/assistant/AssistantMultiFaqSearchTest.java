package com.vitaloom.backend.assistant;

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
    private final AssistantProvider provider = mock(AssistantProvider.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final FindIterable<Document> documents = mock(FindIterable.class);
    private final AssistantFixtureSearch search = new AssistantFixtureSearch(mongo, provider);

    @BeforeEach
    void setup() {
        when(mongo.getCollection("assistant_ready_faq")).thenReturn(collection);
        when(provider.embed(eq("gemini-embedding-2"), nullable(String.class), anyString(), anyBoolean(), eq(768)))
                .thenReturn(Collections.nCopies(768, 1f));
        when(collection.find(any(Bson.class))).thenReturn(documents);
    }

    @Test
    void returnsApprovedEvidenceWithSourceAndNoModelSynthesis() {
        candidates(row("source-a", .89));
        when(documents.first()).thenReturn(faq("source-a"), (Document) null);

        var selected = search.retrieveReadyEvidence(QUESTION, UUID.randomUUID());

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().evidence()).isEqualTo(
                new AssistantProvider.FaqEvidence("source-a", "source-a question", "source-a answer"));
        assertThat(selected.getFirst().source()).isEqualTo(new AssistantCourseAnswers.Source(
                "source-a", 1, "source-a title", "", null, null));
        assertThat(selected.getFirst().healthEducation()).isTrue();
        assertThat(selected.getFirst().current()).isFalse();
        verify(provider, never()).converse(anyList(), anyList(), any());
    }

    @Test
    void rejectsIneligibleMetadataDeduplicatesEvidenceAndCapsAtThree() {
        candidates(10, row("draft", .97), row("one", .96), row("two", .95), row("duplicate", .94),
                row("fourth", .93));
        when(documents.first()).thenReturn(null, faq("one", "Café answer"), faq("two", "Second answer"),
                faq("duplicate", "Cafe\u0301   ANSWER"), faq("fourth", "Fourth answer"));

        var selected = search.retrieveReadyEvidence(QUESTION, UUID.randomUUID());

        assertThat(selected).extracting(item -> item.evidence().id()).containsExactly("one", "two", "fourth");
        var filters = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(5)).find(filters.capture());
        assertThat(filters.getAllValues()).allSatisfy(filter -> {
            assertThat(filter).isInstanceOf(Document.class);
            var query = (Document) filter;
            assertThat(query.getString("status")).isEqualTo("APPROVED");
            assertThat(query.getString("releaseGate")).isEqualTo("READY");
            assertThat(query.getBoolean("active")).isTrue();
            assertThat(query.get("routingOnly")).isEqualTo(new Document("$ne", true));
        });
        var aggregate = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(collection).aggregate(aggregate.capture());
        var vectorSearch = (Document) aggregate.getValue().getFirst();
        assertThat(vectorSearch.get("$vectorSearch", Document.class).getBoolean("exact")).isTrue();
    }

    @Test
    void stopsWhenSimilarityIsBelowThreshold() {
        candidates(row("weak", .749));
        assertThat(search.retrieveReadyEvidence(QUESTION, UUID.randomUUID())).isEmpty();
        verify(collection, never()).find(any(Bson.class));
    }

    @Test
    void propagatesRetrievalDependencyFailure() {
        var failure = new IllegalStateException("search unavailable");
        when(provider.embed(eq("gemini-embedding-2"), nullable(String.class), anyString(), anyBoolean(), eq(768)))
                .thenThrow(failure);
        assertThatThrownBy(() -> search.retrieveReadyEvidence(QUESTION, UUID.randomUUID())).isSameAs(failure);
    }

    @SuppressWarnings("unchecked")
    private void candidates(Document... rows) {
        candidates(10, rows);
    }

    @SuppressWarnings("unchecked")
    private void candidates(int limit, Document... rows) {
        AggregateIterable<Document> aggregate = mock(AggregateIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        var iterator = List.of(rows).iterator();
        when(collection.aggregate(argThat(pipeline -> pipeline.getFirst() instanceof Document stage
                && Integer.valueOf(limit).equals(stage.get("$vectorSearch", Document.class).getInteger("limit")))))
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
        return row(id, 1).append("question", id + " question").append("answer", answer).append("title", id + " title")
                .append("type", "HEALTH_EDUCATION");
    }
}
