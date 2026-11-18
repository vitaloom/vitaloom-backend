package com.vitaloom.backend.assistant;

import static com.vitaloom.backend.assistant.AssistantVoiceActions.answer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AssistantVoiceTurns {
    private static final Duration RETENTION = Duration.ofDays(7);
    private final MongoTemplate mongo;
    private final Clock clock;
    private final AssistantSpeechToText stt;
    private final AssistantVoiceTools.Selector selector;
    private final AssistantVoiceActions actions;

    AssistantVoiceTurns(MongoTemplate mongo, Clock clock, AssistantSpeechToText stt,
            AssistantVoiceTools.Selector selector, AssistantVoiceActions actions) {
        this.mongo = mongo;
        this.clock = clock;
        this.stt = stt;
        this.selector = selector;
        this.actions = actions;
        AssistantCourseController.ensureConversationIndexes(mongo);
        mongo.indexOps(Claim.class).createIndex(new Index().named("assistantVoiceClaimExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    AssistantCourseController.View send(String owner, UUID turnId, byte[] audio) {
        Instant receivedAt = clock.instant();
        var claim = new Claim(owner + ":" + turnId, digest(audio), UUID.randomUUID().toString(),
                receivedAt.plus(RETENTION));
        try {
            mongo.insert(claim);
        } catch (DuplicateKeyException duplicate) {
            return replay(owner, claim);
        }

        String transcript;
        try {
            transcript = stt.transcribe(audio);
        } catch (LocalAssistantSpeechToText.SpeechTranscriptionException failure) {
            // The metric writes have not started. A later attempt may safely reuse this ID.
            mongo.remove(claim);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Speech transcription is unavailable.");
        }
        if (transcript == null || transcript.isBlank() || transcript.length() > 300) {
            mongo.remove(claim);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No supported transcript was recognized.");
        }

        var reply = process(owner, transcript, receivedAt);
        var message = new AssistantConversation.Turn(turnId.toString(), transcript, receivedAt, reply);
        return AssistantCourseController.view(mongo.insert(new AssistantConversation(
                claim.conversationId(), owner, null, clock.instant(), claim.expiresAt(), false, List.of(message))));
    }

    private AssistantCourseAnswers.Answer process(String owner, String transcript, Instant receivedAt) {
        var request = new AssistantVoiceText(transcript, receivedAt.atZone(AssistantVoiceActions.ZONE).toLocalDate());
        if (!request.isCreateRequest() || request.requestsWater() == request.requestsSleep()
                || request.requestsWater() && !request.hasSupportedAmount()) {
            return actions.apply(owner, transcript, receivedAt, null);
        }
        ToolCall tool;
        try {
            tool = selector.select(transcript);
        } catch (RuntimeException failure) {
            return answer("DEPENDENCY_UNAVAILABLE", "Assistant đang tạm thời không xử lý được yêu cầu ghi nhật ký.");
        }
        try {
            return actions.apply(owner, transcript, receivedAt, tool);
        } catch (DataAccessException failure) {
            return answer("DEPENDENCY_UNAVAILABLE",
                    "Chưa thể xác nhận bản ghi. Hãy kiểm tra nhật ký trước khi gửi lại yêu cầu.");
        }
    }

    private AssistantCourseController.View replay(String owner, Claim requested) {
        var claim = mongo.findById(requested.id(), Claim.class);
        if (claim == null || !claim.expiresAt().isAfter(clock.instant()) || !claim.digest().equals(requested.digest())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        var row = mongo.findOne(Query.query(Criteria.where("id").is(claim.conversationId())
                .and("owner").is(owner).and("expiresAt").gt(clock.instant())), AssistantConversation.class);
        if (row == null) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return AssistantCourseController.view(row);
    }

    private static String digest(byte[] audio) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Document("assistant_voice_turn_claims")
    record Claim(@Id String id, String digest, String conversationId, Instant expiresAt) {}
}
