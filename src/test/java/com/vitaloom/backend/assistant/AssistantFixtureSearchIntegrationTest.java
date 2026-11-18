package com.vitaloom.backend.assistant;

import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_ATLAS_TEST_URI", matches = "mongodb://127\\.0\\.0\\.1:.*")
class AssistantFixtureSearchIntegrationTest {
    @Test
    void indexesSyntheticEmbeddingsAndRejectsRetiredEvidence() throws InterruptedException {
        try (var client = MongoClients.create(System.getenv("ASSISTANT_ATLAS_TEST_URI"))) {
            String database = "assistant_atlas_test_" + UUID.randomUUID().toString().replace("-", "");
            var mongo = new MongoTemplate(client, database);
            var provider = mock(GeminiAssistantProvider.class);
            var vector = new ArrayList<>(Collections.nCopies(768, 0.0f));
            vector.set(0, 1.0f);
            when(provider.embed(eq("gemini-embedding-2"), anyString(), any(EmbedContentConfig.class)))
                    .thenReturn(EmbedContentResponse.builder()
                            .embeddings(List.of(ContentEmbedding.builder().values(vector).build())).build());
            when(provider.confirmsFixture(AssistantFixture.ANSWER)).thenReturn(true);
            var search = new AssistantFixtureSearch(mongo, provider);
            try {
                search.ingest();
                var collection = mongo.getCollection("assistant_sandbox_faq");
                var definition = Document.parse("""
                        {"fields": [
                          {"type": "vector", "path": "embedding", "numDimensions": 768, "similarity": "cosine"},
                          {"type": "filter", "path": "status"}, {"type": "filter", "path": "language"},
                          {"type": "filter", "path": "domain"}, {"type": "filter", "path": "generation"}
                        ]}
                        """);
                mongo.getDb().runCommand(new Document("createSearchIndexes", collection.getNamespace().getCollectionName())
                        .append("indexes", List.of(new Document("name", "assistant_fixture_v1")
                                .append("type", "vectorSearch").append("definition", definition))));
                var key = new Document("_id", "app-help-sleep-journal@1");
                var rejected = new Document(collection.find(key).first())
                        .append("_id", "draft").append("status", "DRAFT");
                collection.insertOne(rejected);
                boolean matched = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (System.nanoTime() < deadline && !matched) {
                    var index = collection.listSearchIndexes().first();
                    if (index != null && index.getBoolean("queryable", false)) {
                        matched = search.answer(() -> true);
                    }
                    if (!matched) Thread.sleep(500);
                }
                assertThat(matched).isTrue();
                collection.updateOne(key, new Document("$set", new Document("status", "RETIRED")));
                search.ingest();
                assertThat(search.answer(() -> true)).isFalse();
                verify(provider, times(3)).embed(eq("gemini-embedding-2"), startsWith("title:"),
                        any(EmbedContentConfig.class));
                verify(provider, times(1)).confirmsFixture(AssistantFixture.ANSWER);
            } finally {
                mongo.getDb().drop();
            }
        }
    }
}
