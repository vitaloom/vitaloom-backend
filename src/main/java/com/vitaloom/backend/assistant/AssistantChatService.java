package com.vitaloom.backend.assistant;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

final class AssistantChatService {
    private static final String COLLECTION = "assistant_sandbox_turns";
    private static final Duration RETRY_WINDOW = Duration.ofHours(24);
    private final MongoTemplate mongo;
    private final AssistantProvider provider;

    AssistantChatService(MongoTemplate mongo, AssistantProvider provider) {
        this.mongo = mongo;
        this.provider = provider;
        mongo.indexOps(COLLECTION).createIndex(new Index().named("assistantSandboxTurnExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    AssistantTurn.Status answer(String subject, UUID turnId) {
        return answer(subject, turnId, null, provider::confirmsFixture);
    }

    AssistantTurn.Status answer(String subject, UUID turnId, BooleanSupplier current) {
        return answer(subject, turnId, null, () -> current.getAsBoolean() && provider.confirmsFixture());
    }

    AssistantTurn.Status answer(String subject, UUID turnId, String fixture, BooleanSupplier check) {
        String id = subject + ":" + turnId;
        Instant expiresAt = Instant.now().plus(RETRY_WINDOW).truncatedTo(ChronoUnit.MILLIS);
        try {
            mongo.insert(new AssistantTurn(id, AssistantTurn.Status.PENDING, expiresAt, fixture), COLLECTION);
        } catch (DuplicateKeyException duplicate) {
            var existing = mongo.findById(id, AssistantTurn.class, COLLECTION);
            // A missing claim after a duplicate can be a TTL race. Do not send from this attempt.
            return existing == null || !Instant.now().isBefore(existing.expiresAt())
                    || !Objects.equals(fixture, existing.fixture())
                    ? AssistantTurn.Status.PENDING : existing.status();
        }

        AssistantTurn.Status result = checkProvider(check);
        var claim = Query.query(Criteria.where("id").is(id).and("expiresAt").is(expiresAt)
                .and("status").is(AssistantTurn.Status.PENDING));
        var update = mongo.updateFirst(claim, Update.update("status", result), AssistantTurn.class, COLLECTION);
        return update.wasAcknowledged() && update.getMatchedCount() == 1
                ? result : AssistantTurn.Status.PENDING;
    }

    private AssistantTurn.Status checkProvider(BooleanSupplier check) {
        try {
            if (check.getAsBoolean()) {
                return AssistantTurn.Status.ANSWERED;
            }
        } catch (RuntimeException ignored) {
            // SDK errors may include request material. Return the same bounded dependency outcome.
        }
        return AssistantTurn.Status.DEPENDENCY_UNAVAILABLE;
    }
}
