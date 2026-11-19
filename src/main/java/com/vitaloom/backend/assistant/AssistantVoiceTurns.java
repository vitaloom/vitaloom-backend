package com.vitaloom.backend.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

final class AssistantVoiceTurns {
    private final MongoTemplate mongo;
    private final Clock clock;
    private final AssistantSpeechToText stt;
    private final AssistantConversationTurns turns;
    private final AssistantGeminiReadiness readiness;

    AssistantVoiceTurns(MongoTemplate mongo, Clock clock, AssistantSpeechToText stt,
            AssistantConversationTurns turns, AssistantGeminiReadiness readiness) {
        this.mongo = mongo;
        this.clock = clock;
        this.stt = stt;
        this.turns = turns;
        this.readiness = readiness;
        mongo.indexOps(Claim.class).createIndex(new Index().named("assistantVoiceClaimExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    AssistantCourseController.View send(Authentication auth, UUID conversationId, UUID turnId, byte[] audio,
            AssistantAccountSettings.Preference preference) {
        turns.requireCurrent(auth, preference);
        String owner = auth.getName();
        Instant receivedAt = clock.instant();
        String digest = AssistantConversationTurns.digest(audio);
        String inputKey = "audio:" + digest;
        String claimId = owner + ":" + turnId;
        var conversation = conversationId == null ? null : turns.getOwned(owner, conversationId);
        var claim = new Claim(claimId, digest,
                conversationId == null ? UUID.randomUUID().toString() : conversationId.toString(),
                conversation == null ? receivedAt.plus(Duration.ofDays(7)) : conversation.expiresAt(),
                preference.generation());
        boolean newClaim = true;
        try {
            mongo.insert(claim);
        } catch (DuplicateKeyException duplicate) {
            newClaim = false;
            claim = mongo.findById(claimId, Claim.class);
            if (claim == null || !claim.expiresAt().isAfter(receivedAt) || !claim.digest().equals(digest)
                    || claim.generation() != preference.generation()
                    || (conversationId != null && !claim.conversationId().equals(conversationId.toString()))) {
                throw AssistantConversationTurns.conflict();
            }
            if (conversation == null) {
                try {
                    conversation = turns.getOwned(owner, UUID.fromString(claim.conversationId()));
                } catch (ResponseStatusException missing) {
                    if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
                    throw AssistantConversationTurns.conflict();
                }
            }
        }
        if (conversation != null) {
            if (AssistantConversationTurns.replay(conversation, turnId, null, inputKey) != null) {
                // A legacy turn has no digest of its own. Only its original audio claim may replay it.
                if (newClaim) throw AssistantConversationTurns.conflict();
                turns.requireCurrent(auth, preference);
                return AssistantCourseController.view(conversation, preference);
            }
            AssistantConversationTurns.requireCapacity(conversation);
        }

        String transcript;
        try {
            turns.requireCurrent(auth, preference);
            readiness.requireReady();
            transcript = stt.transcribe(audio);
            turns.requireCurrent(auth, preference);
            if (transcript == null || transcript.isBlank() || transcript.length() > 300) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No supported transcript was recognized.");
            }
        } catch (AssistantSpeechToText.SpeechTranscriptionException failure) {
            if (newClaim && conversation == null) mongo.remove(claim);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Speech transcription is unavailable.");
        } catch (RuntimeException failure) {
            if (newClaim && conversation == null) mongo.remove(claim);
            throw failure;
        }

        UUID target = UUID.fromString(claim.conversationId());
        if (conversation == null) turns.createIfNew(auth, preference, target);
        return turns.submit(auth, preference, target, turnId, transcript.strip(), inputKey, receivedAt);
    }

    @Document("assistant_voice_turn_claims")
    record Claim(@Id String id, String digest, String conversationId, Instant expiresAt, long generation) {}
}
