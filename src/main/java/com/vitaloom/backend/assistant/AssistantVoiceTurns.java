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
    private final AssistantAccountSettings accountSettings;
    private final AssistantGeminiReadiness readiness;

    AssistantVoiceTurns(MongoTemplate mongo, Clock clock, AssistantSpeechToText stt,
            AssistantVoiceTools.Selector selector, AssistantVoiceActions actions,
            AssistantAccountSettings accountSettings, AssistantGeminiReadiness readiness) {
        this.mongo = mongo;
        this.clock = clock;
        this.stt = stt;
        this.selector = selector;
        this.actions = actions;
        this.accountSettings = accountSettings;
        this.readiness = readiness;
        AssistantCourseController.ensureConversationIndexes(mongo);
        mongo.indexOps(Claim.class).createIndex(new Index().named("assistantVoiceClaimExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    AssistantCourseController.View send(String owner, UUID turnId, byte[] audio) {
        return send(owner, turnId, audio, accountSettings.requireEnabled(owner));
    }

    AssistantCourseController.View send(String owner, UUID turnId, byte[] audio,
            AssistantAccountSettings.Preference preference) {
        requireCurrent(owner, preference);
        readiness.requireReady();
        Instant receivedAt = clock.instant();
        var claim = new Claim(owner + ":" + turnId, digest(audio), UUID.randomUUID().toString(),
                receivedAt.plus(RETENTION), preference.generation());
        try {
            mongo.insert(claim);
        } catch (DuplicateKeyException duplicate) {
            return replay(owner, claim, preference);
        }

        String transcript;
        try {
            requireCurrent(owner, preference);
            readiness.requireReady();
            transcript = stt.transcribe(audio);
        } catch (AssistantSpeechToText.SpeechTranscriptionException failure) {
            // The metric writes have not started. A later attempt may safely reuse this ID.
            mongo.remove(claim);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Speech transcription is unavailable.");
        }
        requireCurrentOrRemoveClaim(owner, claim, preference);
        if (transcript == null || transcript.isBlank() || transcript.length() > 300) {
            mongo.remove(claim);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No supported transcript was recognized.");
        }

        var reply = process(owner, transcript, receivedAt, preference);
        requireCurrent(owner, preference);
        var message = new AssistantConversation.Turn(turnId.toString(), transcript, receivedAt, reply);
        var saved = mongo.insert(new AssistantConversation(
                claim.conversationId(), owner, null, clock.instant(), claim.expiresAt(), false, List.of(message)));
        requireCurrent(owner, preference);
        return AssistantCourseController.view(saved, preference);
    }

    private AssistantCourseAnswers.Answer process(String owner, String transcript, Instant receivedAt,
            AssistantAccountSettings.Preference preference) {
        requireCurrent(owner, preference);
        ToolCall tool;
        readiness.requireReady();
        requireCurrent(owner, preference);
        try {
            tool = selector.select(transcript);
        } catch (AssistantAccountSettingsController.AccountAccessRequiredException changed) {
            throw changed;
        } catch (RuntimeException failure) {
            return answer("DEPENDENCY_UNAVAILABLE", "Assistant đang tạm thời không xử lý được yêu cầu ghi nhật ký.");
        }
        requireCurrent(owner, preference);
        try {
            var result = actions.apply(owner, transcript, receivedAt, tool);
            requireCurrent(owner, preference);
            return result;
        } catch (DataAccessException failure) {
            requireCurrent(owner, preference);
            return answer("DEPENDENCY_UNAVAILABLE",
                    "Chưa thể xác nhận bản ghi. Hãy kiểm tra nhật ký trước khi gửi lại yêu cầu.");
        }
    }

    private AssistantCourseController.View replay(String owner, Claim requested,
            AssistantAccountSettings.Preference preference) {
        requireCurrent(owner, preference);
        var claim = mongo.findById(requested.id(), Claim.class);
        if (claim == null || !claim.expiresAt().isAfter(clock.instant()) || !claim.digest().equals(requested.digest())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (claim.generation() != preference.generation()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var row = mongo.findOne(Query.query(Criteria.where("id").is(claim.conversationId())
                .and("owner").is(owner).and("expiresAt").gt(clock.instant())), AssistantConversation.class);
        if (row == null) throw new ResponseStatusException(HttpStatus.CONFLICT);
        requireCurrent(owner, preference);
        return AssistantCourseController.view(row, preference);
    }

    private void requireCurrentOrRemoveClaim(String owner, Claim claim,
            AssistantAccountSettings.Preference preference) {
        try {
            requireCurrent(owner, preference);
        } catch (AssistantAccountSettingsController.AccountAccessRequiredException changed) {
            mongo.remove(Query.query(Criteria.where("id").is(claim.id())), Claim.class);
            throw changed;
        }
    }

    private void requireCurrent(String owner, AssistantAccountSettings.Preference preference) {
        if (preference == null || !preference.personalDataAllowed() || !accountSettings.isCurrent(owner, preference)) {
            throw new AssistantAccountSettingsController.AccountAccessRequiredException();
        }
    }

    private static String digest(byte[] audio) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Document("assistant_voice_turn_claims")
    record Claim(@Id String id, String digest, String conversationId, Instant expiresAt, long generation) {}
}
