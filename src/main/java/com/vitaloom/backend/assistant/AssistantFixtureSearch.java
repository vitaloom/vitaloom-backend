package com.vitaloom.backend.assistant;

import com.google.genai.types.EmbedContentConfig;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.MongoClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

final class AssistantFixtureSearch implements AutoCloseable {
    private static final String COLLECTION = "assistant_sandbox_faq";
    private static final String INDEX = "assistant_fixture_v1";
    private static final int DIMENSIONS = 768;

    static final AssistantCourseAnswers.Source SOURCE = new AssistantCourseAnswers.Source(
            "app-help-sleep-journal", 1, "Nhật ký giấc ngủ", "/sleep/journal", null, null);

    private final MongoTemplate mongo;
    private final GeminiAssistantProvider provider;
    private final MongoClient vectorClient;
    private final String model;
    private final boolean legacyModel;
    private final Document filter;

    AssistantFixtureSearch(MongoTemplate mongo, GeminiAssistantProvider provider) {
        this(mongo, provider, null, "gemini-embedding-2");
    }

    AssistantFixtureSearch(MongoTemplate mongo, GeminiAssistantProvider provider, MongoClient vectorClient,
            String model) {
        if (!List.of("gemini-embedding-2", "gemini-embedding-001").contains(model)) {
            throw new IllegalArgumentException("Unsupported Assistant embedding model");
        }
        this.mongo = mongo;
        this.provider = provider;
        this.vectorClient = vectorClient;
        this.model = model;
        legacyModel = "gemini-embedding-001".equals(model);
        filter = new Document("status", "APPROVED").append("language", "vi").append("domain", "APP_GUIDANCE")
                .append("generation", "fixture-v1-" + model + "-" + DIMENSIONS);
    }

    @Override
    public void close() {
        if (vectorClient != null) vectorClient.close();
    }

    void ingest() {
        for (var faq : AssistantFixture.CORPUS) ingest(faq);
    }

    private String chunkKey(AssistantFixture.Faq faq) {
        return faq.id() + "@1" + (legacyModel ? ":" + model : "");
    }

    private void ingest(AssistantFixture.Faq faq) {
        var collection = mongo.getCollection(COLLECTION);
        var key = new Document("_id", chunkKey(faq));
        if (collection.find(key).first() != null) return;

        var vector = embed(faq.title(), faq.answer(), true);
        var row = new Document(filter).append("answer", faq.answer())
                .append("embedding", vector);
        // Reingestion must preserve a retired chunk with this immutable version ID.
        collection.updateOne(key, new Document("$setOnInsert", row), new UpdateOptions().upsert(true));
    }

    boolean answer(BooleanSupplier current) {
        return answer(AssistantFixture.JOURNAL, AssistantFixture.QUESTION, current);
    }

    boolean answerSyntheticSleep(String day, BooleanSupplier current, BooleanSupplier verifyRecord) {
        return provider.confirmsSyntheticSleep(day, current, verifyRecord);
    }

    boolean answer(AssistantFixture.Faq faq, String text, BooleanSupplier current) {
        if (!current.getAsBoolean()) return false;
        String question = text.strip().equalsIgnoreCase(faq.paraphrase()) ? faq.paraphrase() : faq.question();
        var query = embed(faq.title(), question, false);
        var search = new Document("index", INDEX).append("path", "embedding")
                .append("queryVector", query).append("exact", true).append("limit", 4)
                .append("filter", filter);
        var selected = mongo.getCollection(COLLECTION).aggregate(List.of(
                new Document("$vectorSearch", search),
                new Document("$project", new Document("_id", 1))))
                .maxTime(3, TimeUnit.SECONDS)
                .into(new java.util.ArrayList<>());
        String key = chunkKey(faq);
        if (selected.stream().noneMatch(row -> key.equals(row.getString("_id")))) return false;

        // The search index can lag. Recheck against the primary document and the server fixture.
        var active = mongo.getCollection(COLLECTION).find(new Document(filter).append("_id", key)
                .append("answer", faq.answer())).first();
        return active != null && current.getAsBoolean()
                && (faq == AssistantFixture.JOURNAL && question.equals(AssistantFixture.QUESTION)
                        ? provider.confirmsFixture(active.getString("answer"))
                        : provider.confirmsFixture(question, active.getString("answer"))) && current.getAsBoolean();
    }

    AssistantCourseAnswers.Answer answerReadyFaq(String question) {
        if (!model.equals("gemini-embedding-2")) {
            throw new IllegalStateException("READY FAQ vector index requires gemini-embedding-2");
        }
        var query = embed("", question, false);
        var collection = mongo.getCollection("assistant_ready_faq");
        var search = new Document("index", "assistant_ready_faq_v1").append("path", "embedding")
                .append("queryVector", query).append("exact", true).append("limit", 3)
                .append("filter", new Document("active", true));
        var candidates = collection.aggregate(List.of(new Document("$vectorSearch", search),
                new Document("$project", new Document("version", 1).append("contentHash", 1)
                        .append("score", new Document("$meta", "vectorSearchScore")))))
                .maxTime(3, TimeUnit.SECONDS);
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString();
        for (var candidate : candidates) {
            if (candidate.getDouble("score") < 0.75) break;
            var filter = new Document("_id", candidate.getString("_id"))
                    .append("active", true).append("status", "APPROVED").append("releaseGate", "READY")
                    .append("version", candidate.getInteger("version"))
                    .append("contentHash", candidate.getString("contentHash"))
                    .append("supersededBy", null).append("model", model)
                    .append("effectiveAt", new Document("$lte", today));
            var faq = collection.find(filter).first();
            if (faq == null) continue;
            String reply = provider.answerFaq(question, faq.getString("question"), faq.getString("answer"));
            if (reply == null) break;
            if (collection.find(filter).first() == null) break;
            var source = new AssistantCourseAnswers.Source(faq.getString("_id"), faq.getInteger("version"),
                    faq.getString("title"), "", null, null);
            return new AssistantCourseAnswers.Answer("ANSWERED",
                    List.of(new AssistantCourseAnswers.Part("ANSWERED", reply, source)), null);
        }
        return new AssistantCourseAnswers.Answer("INSUFFICIENT_EVIDENCE",
                List.of(new AssistantCourseAnswers.Part("INSUFFICIENT_EVIDENCE",
                        "Không tìm thấy FAQ phù hợp. No matching FAQ was found.", null)), null);
    }

    private List<Float> embed(String title, String text, boolean document) {
        var config = EmbedContentConfig.builder().outputDimensionality(DIMENSIONS);
        String input = text;
        if (legacyModel) {
            config.taskType(document ? "RETRIEVAL_DOCUMENT" : "QUESTION_ANSWERING");
            if (document) config.title(title);
        } else {
            input = (document ? "title: " + title + " | text: " : "task: question answering | query: ") + text;
        }
        var response = provider.embed(model, input, config.build());
        var embeddings = response.embeddings().orElseThrow();
        if (embeddings.size() != 1) throw new IllegalStateException("Unexpected embedding count");
        var vector = embeddings.getFirst().values().orElseThrow();
        if (vector.size() != DIMENSIONS || vector.stream().anyMatch(value -> !Float.isFinite(value))) {
            throw new IllegalStateException("Invalid FAQ embedding");
        }
        return vector;
    }
}
