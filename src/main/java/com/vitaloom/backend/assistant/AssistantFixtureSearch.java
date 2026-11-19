package com.vitaloom.backend.assistant;

import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

final class AssistantFixtureSearch implements AutoCloseable {
    private static final String COLLECTION = "assistant_sandbox_faq";
    private static final String INDEX = "assistant_fixture_v1";
    private static final int DIMENSIONS = 768;
    private static final int FAQ_EVIDENCE_LIMIT = 3;
    private static final double FAQ_SCORE_THRESHOLD = 0.75;
    private static final Logger LOG = LoggerFactory.getLogger(AssistantFixtureSearch.class);

    static final AssistantCourseAnswers.Source SOURCE = new AssistantCourseAnswers.Source(
            "app-help-sleep-journal", 1, "Nhật ký giấc ngủ", "/sleep/journal", null, null);

    private final MongoTemplate mongo;
    private final AssistantProvider provider;
    private final MongoClient vectorClient;
    private final String model;
    private final boolean legacyModel;
    private final Document filter;

    AssistantFixtureSearch(MongoTemplate mongo, AssistantProvider provider) {
        this(mongo, provider, null, "gemini-embedding-2");
    }

    AssistantFixtureSearch(MongoTemplate mongo, AssistantProvider provider, MongoClient vectorClient,
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
                && provider.confirmsFixture(question, active.getString("answer")) && current.getAsBoolean();
    }

    List<ReadyEvidence> retrieveReadyEvidence(String question, UUID requestId) {
        return retrieveReadyEvidence(question, requestId, 10);
    }

    private List<ReadyEvidence> retrieveReadyEvidence(String question, UUID requestId, int topK) {
        if (topK != 5 && topK != 10) throw new IllegalArgumentException("Unsupported READY FAQ topK");
        long started = System.nanoTime();
        String stage = "embedding";
        String outcome = "dependency_error";
        try {
            if (!model.equals("gemini-embedding-2")) {
                throw new IllegalStateException("READY FAQ vector index requires gemini-embedding-2");
            }
            var query = embed("", question, false);
            stage = "vector_search";
            var collection = mongo.getCollection("assistant_ready_faq");
            var candidates = rankedReadyFaqs(collection, query, topK);
            String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString();
            var selected = new java.util.LinkedHashMap<String, ReadyEvidence>();
            var canonicalAnswers = new java.util.HashSet<String>();
            outcome = "empty_results";
            try (var cursor = candidates.iterator()) {
                int rank = 0;
                while (rank < topK && selected.size() < FAQ_EVIDENCE_LIMIT && cursor.hasNext()) {
                    var candidate = cursor.next();
                    rank++;
                    double score = candidate.getDouble("score");
                    if (score < FAQ_SCORE_THRESHOLD) {
                        logContextCandidate(requestId, candidate, rank, "not_checked", "below_threshold", topK);
                        if (selected.isEmpty()) outcome = "below_threshold";
                        break;
                    }
                    stage = "metadata_check";
                    var eligibility = readyFaqFilter(candidate, today);
                    var faq = collection.find(eligibility).first();
                    if (faq == null || !nonblank(faq, "_id", "title", "question", "answer")) {
                        logContextCandidate(requestId, candidate, rank, "rejected", "metadata_rejected", topK);
                        if (selected.isEmpty()) outcome = "metadata_rejected";
                        continue;
                    }
                    String id = faq.getString("_id");
                    String canonicalAnswer = java.text.Normalizer.normalize(faq.getString("answer"),
                            java.text.Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip()
                            .toLowerCase(java.util.Locale.ROOT);
                    if (selected.containsKey(id) || !canonicalAnswers.add(canonicalAnswer)) {
                        logContextCandidate(requestId, candidate, rank, "accepted", "duplicate", topK);
                        continue;
                    }
                    selected.put(id, readyEvidence(collection, faq, today));
                    logContextCandidate(requestId, candidate, rank, "accepted", "selected", topK);
                    outcome = "selected";
                }
            }
            outcome = selected.isEmpty() ? "no_evidence" : "evidence_retrieved";
            return List.copyOf(selected.values());
        } catch (RuntimeException failure) {
            outcome = failureReason(failure);
            throw failure;
        } finally {
            LOG.atDebug().addKeyValue("request_id", requestId).addKeyValue("embedding_model", model)
                    .addKeyValue("embedding_dimensions", DIMENSIONS).addKeyValue("strategy", "multi_faq_context")
                    .addKeyValue("top_k", topK).addKeyValue("stage", stage).addKeyValue("outcome", outcome)
                    .addKeyValue("latency_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Assistant FAQ evidence retrieval");
        }
    }

    private ReadyEvidence readyEvidence(MongoCollection<Document> collection, Document faq, String today) {
        var evidence = new AssistantProvider.FaqEvidence(
                faq.getString("_id"), faq.getString("question"), faq.getString("answer"));
        var source = new AssistantCourseAnswers.Source(faq.getString("_id"), faq.getInteger("version"),
                faq.getString("title"), "", null, null);
        var eligibility = readyFaqFilter(faq, today);
        String type = faq.getString("type");
        if (type != null) eligibility.append("type", type);
        return new ReadyEvidence(evidence, source, "HEALTH_EDUCATION".equals(type),
                () -> nonblank(collection.find(eligibility).first(), "_id", "title", "question", "answer"));
    }

    static final class ReadyEvidence {
        private final AssistantProvider.FaqEvidence evidence;
        private final AssistantCourseAnswers.Source source;
        private final boolean healthEducation;
        private final BooleanSupplier current;

        private ReadyEvidence(AssistantProvider.FaqEvidence evidence, AssistantCourseAnswers.Source source,
                boolean healthEducation, BooleanSupplier current) {
            this.evidence = evidence;
            this.source = source;
            this.healthEducation = healthEducation;
            this.current = current;
        }

        AssistantProvider.FaqEvidence evidence() { return evidence; }
        AssistantCourseAnswers.Source source() { return source; }
        boolean healthEducation() { return healthEducation; }
        boolean current() { return current.getAsBoolean(); }
    }

    private static AggregateIterable<Document> rankedReadyFaqs(
            MongoCollection<Document> collection, List<Float> query, int limit) {
        var search = new Document("index", "assistant_ready_faq_v1").append("path", "embedding")
                .append("queryVector", query).append("exact", true).append("limit", limit)
                .append("filter", new Document("active", true));
        return collection.aggregate(List.of(new Document("$vectorSearch", search),
                new Document("$project", new Document("version", 1).append("contentHash", 1)
                        .append("score", new Document("$meta", "vectorSearchScore")))))
                .maxTime(3, TimeUnit.SECONDS);
    }

    private Document readyFaqFilter(Document candidate, String today) {
        return new Document("_id", candidate.getString("_id")).append("active", true).append("status", "APPROVED")
                .append("releaseGate", "READY").append("version", candidate.getInteger("version"))
                .append("contentHash", candidate.getString("contentHash")).append("supersededBy", null)
                .append("routingOnly", new Document("$ne", true))
                .append("model", model).append("effectiveAt", new Document("$lte", today))
                .append("approvedBy", new Document("$regex", "\\S"))
                .append("reviewedAt", new Document("$regex", "^\\d{4}-\\d{2}-\\d{2}$"));
    }

    private static boolean nonblank(Document document, String... fields) {
        if (document == null) return false;
        for (String field : fields) {
            Object value = document.get(field);
            if (!(value instanceof String text) || text.isBlank()) return false;
        }
        return true;
    }

    private static void logContextCandidate(UUID requestId, Document candidate, int rank, String metadata,
            String decision, int topK) {
        LOG.atDebug().addKeyValue("request_id", requestId).addKeyValue("faq_id", candidate.getString("_id"))
                .addKeyValue("rank", rank).addKeyValue("vector_score", candidate.getDouble("score"))
                .addKeyValue("metric", "cosine").addKeyValue("threshold", FAQ_SCORE_THRESHOLD)
                .addKeyValue("strategy", "multi_faq_context").addKeyValue("top_k", topK)
                .addKeyValue("metadata", metadata).addKeyValue("decision", decision)
                .log("Assistant FAQ context candidate");
    }

    private static String failureReason(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException || cause instanceof com.mongodb.MongoTimeoutException) {
                return "timeout";
            }
            if (cause instanceof com.google.genai.errors.ApiException) return "gemini_api_error";
        }
        return "dependency_error";
    }

    private List<Float> embed(String title, String text, boolean document) {
        var vector = provider.embed(model, title, text, document, DIMENSIONS);
        if (vector.size() != DIMENSIONS || vector.stream().anyMatch(value -> !Float.isFinite(value))) {
            throw new IllegalStateException("Invalid FAQ embedding");
        }
        return vector;
    }
}
