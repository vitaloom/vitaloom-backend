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
    private static final int FAQ_CANDIDATE_LIMIT = 3;
    private static final int FAQ_CONTEXT_LIMIT = 3;
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

    AssistantCourseAnswers.Answer answerPersonalData(String question, AssistantPersonalDataTools tools,
            BooleanSupplier current) {
        provider.selectPersonalTool(question, tools, current);
        return current.getAsBoolean() ? tools.answer() : null;
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

    AssistantCourseAnswers.Answer answerReadyFaq(String question, UUID requestId) {
        long started = System.nanoTime();
        int calls = 0;
        String stage = "embedding";
        String outcome = "dependency_error";
        try {
            if (!model.equals("gemini-embedding-2")) {
                throw new IllegalStateException("READY FAQ vector index requires gemini-embedding-2");
            }
            var query = embed("", question, false);
            stage = "vector_search";
            var collection = mongo.getCollection("assistant_ready_faq");
            var candidates = rankedReadyFaqs(collection, query, FAQ_CANDIDATE_LIMIT);
            String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString();
            outcome = "empty_results";
            try (var cursor = candidates.iterator()) {
                int rank = 0;
                while (rank < FAQ_CANDIDATE_LIMIT && cursor.hasNext()) {
                    var candidate = cursor.next();
                    rank++;
                    double score = candidate.getDouble("score");
                    if (score < FAQ_SCORE_THRESHOLD) {
                        logCandidate(requestId, candidate, rank, "not_checked", "below_threshold");
                        if (calls == 0) outcome = "below_threshold";
                        break;
                    }
                    stage = "metadata_check";
                    var filter = readyFaqFilter(candidate, today);
                    var faq = collection.find(filter).first();
                    if (faq == null) {
                        logCandidate(requestId, candidate, rank, "rejected", "not_called");
                        if (calls == 0) outcome = "metadata_rejected";
                        continue;
                    }
                    stage = "generation";
                    calls++;
                    String reply;
                    try {
                        reply = provider.answerFaq(question, faq.getString("question"), faq.getString("answer"));
                    } catch (RuntimeException failure) {
                        logCandidate(requestId, candidate, rank, "accepted", failureReason(failure));
                        throw failure;
                    }
                    if (reply == null) {
                        logCandidate(requestId, candidate, rank, "accepted", "no_match");
                        outcome = "no_match";
                        continue;
                    }
                    stage = "metadata_recheck";
                    if (collection.find(filter).first() == null) {
                        logCandidate(requestId, candidate, rank, "revoked", "match");
                        outcome = "metadata_revoked";
                        continue;
                    }
                    logCandidate(requestId, candidate, rank, "accepted", "match");
                    outcome = "answered";
                    var source = new AssistantCourseAnswers.Source(faq.getString("_id"), faq.getInteger("version"),
                            faq.getString("title"), "", null, null);
                    return new AssistantCourseAnswers.Answer("ANSWERED",
                            List.of(new AssistantCourseAnswers.Part("ANSWERED", reply, source)), null);
                }
            }
            return insufficientFaqAnswer();
        } catch (RuntimeException failure) {
            outcome = failureReason(failure);
            throw failure;
        } finally {
            LOG.atDebug().addKeyValue("request_id", requestId).addKeyValue("embedding_model", model)
                    .addKeyValue("embedding_dimensions", DIMENSIONS).addKeyValue("stage", stage)
                    .addKeyValue("outcome", outcome).addKeyValue("generation_calls", calls)
                    .addKeyValue("latency_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Assistant FAQ retrieval");
        }
    }

    AssistantCourseAnswers.Answer answerReadyFaqContext(String question, UUID requestId, int topK) {
        return answerReadyFaqContext(question, requestId, topK, java.util.Set.of());
    }

    AssistantCourseAnswers.Answer answerReadySleepGuidance(UUID requestId) {
        return answerReadyFaqContext("Người trưởng thành nên ngủ bao nhiêu tiếng?", requestId, 10,
                java.util.Set.of("READY-MANUAL-001", "READY-MANUAL-002"));
    }

    private AssistantCourseAnswers.Answer answerReadyFaqContext(String question, UUID requestId, int topK,
            java.util.Set<String> allowedIds) {
        if (topK != 5 && topK != 10) throw new IllegalArgumentException("Unsupported READY FAQ topK");
        long started = System.nanoTime();
        int calls = 0;
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
            var selected = new java.util.LinkedHashMap<String, Document>();
            var canonicalAnswers = new java.util.HashSet<String>();
            boolean sleepGuidance = !allowedIds.isEmpty();
            outcome = "empty_results";
            try (var cursor = candidates.iterator()) {
                int rank = 0;
                while (rank < topK && selected.size() < FAQ_CONTEXT_LIMIT && cursor.hasNext()) {
                    var candidate = cursor.next();
                    rank++;
                    if (sleepGuidance && !allowedIds.contains(candidate.getString("_id"))) {
                        logContextCandidate(requestId, candidate, rank, "not_checked", "domain_not_allowed", topK);
                        continue;
                    }
                    double score = candidate.getDouble("score");
                    if (score < FAQ_SCORE_THRESHOLD) {
                        logContextCandidate(requestId, candidate, rank, "not_checked", "below_threshold", topK);
                        if (selected.isEmpty()) outcome = "below_threshold";
                        break;
                    }
                    stage = "metadata_check";
                    var eligibility = readyFaqFilter(candidate, today);
                    if (sleepGuidance) constrainSleepGuidance(eligibility);
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
                    selected.put(id, faq);
                    logContextCandidate(requestId, candidate, rank, "accepted", "selected", topK);
                    outcome = "selected";
                }
            }
            if (selected.isEmpty()) {
                return insufficientFaqAnswer();
            }
            stage = "generation";
            calls = 1;
            var evidence = selected.values().stream().map(faq -> new AssistantProvider.FaqEvidence(
                    faq.getString("_id"), faq.getString("question"), faq.getString("answer"))).toList();
            var reply = provider.answerFaqContext(question, evidence);
            if (reply == null) throw new AssistantProvider.InvalidFaqResponse();
            if (reply.isEmpty()) {
                outcome = "no_match";
                return insufficientFaqAnswer();
            }
            if (reply.size() > FAQ_CONTEXT_LIMIT) throw new AssistantProvider.InvalidFaqResponse();
            var citedIds = new java.util.HashSet<String>();
            var answerParts = new java.util.ArrayList<AssistantCourseAnswers.Part>();
            for (var part : reply) {
                if (part == null || part.faqId() == null || part.faqId().isBlank() || part.answer() == null
                        || part.answer().isBlank() || !citedIds.add(part.faqId()) || !selected.containsKey(part.faqId())) {
                    throw new AssistantProvider.InvalidFaqResponse();
                }
                var faq = selected.get(part.faqId());
                answerParts.add(new AssistantCourseAnswers.Part("ANSWERED",
                        sleepGuidance ? faq.getString("answer") : part.answer(),
                        new AssistantCourseAnswers.Source(faq.getString("_id"), faq.getInteger("version"),
                                faq.getString("title"), "", null, null)));
            }
            stage = "metadata_recheck";
            for (String id : citedIds) {
                var faq = selected.get(id);
                var eligibility = readyFaqFilter(faq, today);
                if (sleepGuidance) constrainSleepGuidance(eligibility);
                if (collection.find(eligibility).first() == null) {
                    outcome = "metadata_revoked";
                    return insufficientFaqAnswer();
                }
            }
            outcome = "answered";
            return new AssistantCourseAnswers.Answer("ANSWERED", List.copyOf(answerParts), null);
        } catch (RuntimeException failure) {
            outcome = failureReason(failure);
            throw failure;
        } finally {
            LOG.atDebug().addKeyValue("request_id", requestId).addKeyValue("embedding_model", model)
                    .addKeyValue("embedding_dimensions", DIMENSIONS).addKeyValue("strategy", "multi_faq_context")
                    .addKeyValue("top_k", topK).addKeyValue("stage", stage).addKeyValue("outcome", outcome)
                    .addKeyValue("generation_calls", calls)
                    .addKeyValue("latency_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Assistant FAQ context retrieval");
        }
    }

    private static void constrainSleepGuidance(Document eligibility) {
        eligibility.append("domain", "SLEEP").append("type", "HEALTH_EDUCATION").append("language", "vi");
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
        for (String field : fields) {
            Object value = document.get(field);
            if (!(value instanceof String text) || text.isBlank()) return false;
        }
        return true;
    }

    private static AssistantCourseAnswers.Answer insufficientFaqAnswer() {
        return new AssistantCourseAnswers.Answer("INSUFFICIENT_EVIDENCE",
                List.of(new AssistantCourseAnswers.Part("INSUFFICIENT_EVIDENCE",
                        "Không tìm thấy FAQ phù hợp. No matching FAQ was found.", null)), null);
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

    private static void logCandidate(UUID requestId, Document candidate, int rank, String metadata, String decision) {
        LOG.atDebug().addKeyValue("request_id", requestId).addKeyValue("faq_id", candidate.getString("_id"))
                .addKeyValue("rank", rank).addKeyValue("vector_score", candidate.getDouble("score"))
                .addKeyValue("metric", "cosine").addKeyValue("threshold", FAQ_SCORE_THRESHOLD)
                .addKeyValue("metadata", metadata).addKeyValue("decision", decision)
                .log("Assistant FAQ candidate");
    }

    private static String failureReason(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AssistantProvider.InvalidFaqResponse) return "invalid_model_response";
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
