package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.service.WaterService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Conditional(AssistantActivation.Course.class)
final class AssistantConversationTurns {
    private static final Duration CONTEXT_AGE = Duration.ofMinutes(30);
    private static final Duration WRITE_SETTLEMENT_TIME = Duration.ofMinutes(2);
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final int MAX_CONTEXT_TURNS = 3;
    private static final int MAX_TURNS = 50;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final AssistantCourseAnswers reads;
    private final ObjectProvider<AssistantFixtureSearch> faq;
    private final ObjectProvider<AssistantProvider> provider;
    private final AssistantGeminiReadiness readiness;
    private final AssistantAccountSettings settings;
    private final AssistantJournalActions journal;
    private boolean receiptIndexesReady;

    AssistantConversationTurns(MongoTemplate mongo, Clock clock, AssistantCourseAnswers reads,
            @Qualifier("assistantReadyFaqSearch") ObjectProvider<AssistantFixtureSearch> faq,
            ObjectProvider<AssistantProvider> provider, AssistantGeminiReadiness readiness,
            AssistantAccountSettings settings, WaterService water, SleepJournalService sleep) {
        this.mongo = mongo;
        this.clock = clock;
        this.reads = reads;
        this.faq = faq;
        this.provider = provider;
        this.readiness = readiness;
        this.settings = settings;
        this.journal = new AssistantJournalActions(water, sleep);
    }

    AssistantCourseController.View submit(Authentication auth, AssistantAccountSettings.Preference preference,
            UUID conversationId, UUID turnId, String text, String inputKey, Instant receivedAt) {
        String owner = auth.getName();
        requireCurrent(auth, preference);
        var row = getOwned(owner, conversationId);
        var existing = replay(row, turnId, text, inputKey);
        if (existing != null) {
            if (existing.generation() != null && existing.generation() != preference.generation()) throw conflict();
            requireCurrent(auth, preference);
            return AssistantCourseController.view(row, preference);
        }
        requireCapacity(row);

        BooleanSupplier current = () -> isCurrent(auth, preference)
                && mongo.exists(owned(owner, row.id()).addCriteria(Criteria.where("revision").is(row.revision())),
                        AssistantConversation.class);
        String receiptId = owner + ":" + turnId;
        var receipt = mongo.findById(receiptId, Receipt.class);
        AssistantCourseAnswers.Answer answer;
        Instant at = receivedAt;
        boolean journalAction;
        if (receipt != null) {
            if (!receipt.conversationId().equals(row.id()) || !receipt.inputKey().equals(inputKey)
                    || receipt.generation() != preference.generation()
                    || !Objects.equals(receipt.revision(), row.revision())) {
                throw conflict();
            }
            try {
                verifyCurrent(auth, preference, current);
                answer = recoverAnswer(receipt);
            } catch (OptimisticLockingFailureException changed) {
                return replayAfterConflict(auth, preference, conversationId, turnId, text, inputKey);
            }
            at = receipt.at();
            journalAction = Boolean.TRUE.equals(receipt.journalAction());
        } else {
            readiness.requireReady();
            var model = provider.getIfAvailable();
            if (model == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            var execution = new WriteExecution(auth, preference, current,
                    new Receipt(receiptId, row.id(), row.revision(), inputKey, preference.generation(),
                            receivedAt, null, row.expiresAt(), null));
            try {
                answer = evaluate(auth, model, row, turnId, text, receivedAt, preference, current, execution);
                journalAction = Boolean.TRUE.equals(execution.claim.journalAction());
                verifyCurrent(auth, preference, current);
                if (!journalAction) {
                    execution.reserve(false);
                    verifyCurrent(auth, preference, current);
                    answer = settle(execution.claim, answer);
                }
            } catch (AssistantNativeChat.AccessChanged changed) {
                return replayAfterConflict(auth, preference, conversationId, turnId, text, inputKey);
            } catch (OptimisticLockingFailureException changed) {
                return replayAfterConflict(auth, preference, conversationId, turnId, text, inputKey);
            }
        }

        var history = new ArrayList<>(row.turns());
        history.add(new AssistantConversation.Turn(turnId.toString(), text, at, answer, inputKey,
                preference.generation(), journalAction));
        AssistantConversation saved;
        try {
            verifyCurrent(auth, preference, current);
            saved = mongo.save(row.change(clock.instant(), true, List.copyOf(history)));
        } catch (OptimisticLockingFailureException changed) {
            return replayAfterConflict(auth, preference, conversationId, turnId, text, inputKey);
        }
        requireCurrent(auth, preference);
        if (!mongo.exists(owned(owner, row.id()), AssistantConversation.class)) throw conflict();
        return AssistantCourseController.view(saved, preference);
    }

    private AssistantCourseController.View replayAfterConflict(Authentication auth,
            AssistantAccountSettings.Preference preference, UUID conversationId, UUID turnId, String text,
            String inputKey) {
        requireCurrent(auth, preference);
        var row = getOwned(auth.getName(), conversationId);
        var appended = replay(row, turnId, text, inputKey);
        if (appended == null || !Objects.equals(appended.generation(), preference.generation())) throw conflict();
        requireCurrent(auth, preference);
        return AssistantCourseController.view(row, preference);
    }

    private AssistantCourseAnswers.Answer recoverAnswer(Receipt receipt) {
        if (receipt.answer() != null) return receipt.answer();
        if (receipt.claimedAt().plus(WRITE_SETTLEMENT_TIME).isAfter(clock.instant())) throw conflict();
        var uncertain = AssistantResponsePolicy.message("DEPENDENCY_UNAVAILABLE", UNCERTAIN_WRITE);
        return settle(receipt, uncertain);
    }

    private AssistantCourseAnswers.Answer settle(Receipt claim, AssistantCourseAnswers.Answer answer) {
        try {
            if (mongo.updateFirst(activeClaim(claim), new Update().set("answer", answer), Receipt.class)
                    .getMatchedCount() == 1) return answer;
        } catch (DataAccessException uncertain) {
            // The update may have committed even if its acknowledgement was lost.
            var saved = settledAnswer(claim);
            if (saved != null) return saved;
            throw uncertain;
        }
        var saved = settledAnswer(claim);
        if (saved != null) return saved;
        throw conflict();
    }

    private AssistantCourseAnswers.Answer settledAnswer(Receipt claim) {
        var saved = mongo.findById(claim.id(), Receipt.class);
        if (saved != null && saved.conversationId().equals(claim.conversationId())
                && saved.inputKey().equals(claim.inputKey()) && Objects.equals(saved.revision(), claim.revision())
                && saved.generation() == claim.generation()) return saved.answer();
        return null;
    }

    private static Query activeClaim(Receipt claim) {
        return Query.query(Criteria.where("id").is(claim.id()).and("answer").is(null).and("discarded").ne(true)
                .and("conversationId").is(claim.conversationId()).and("revision").is(claim.revision())
                .and("generation").is(claim.generation()).and("inputKey").is(claim.inputKey()));
    }

    final class WriteExecution {
        private final Authentication auth;
        private final AssistantAccountSettings.Preference preference;
        private final BooleanSupplier current;
        private Receipt claim;
        private boolean attempted;
        private AssistantCourseAnswers.Answer outcome;

        private WriteExecution(Authentication auth, AssistantAccountSettings.Preference preference,
                BooleanSupplier current, Receipt claim) {
            this.auth = auth;
            this.preference = preference;
            this.current = current;
            this.claim = claim;
        }

        private void reserve(boolean journalAction) {
            verifyCurrent(auth, preference, current);
            ensureReceiptIndexes();
            claim = new Receipt(claim.id(), claim.conversationId(), claim.revision(), claim.inputKey(),
                    claim.generation(), claim.at(), clock.instant(), claim.expiresAt(), null, journalAction);
            try {
                mongo.insert(claim);
            } catch (DuplicateKeyException duplicate) {
                throw conflict();
            }
        }

        void begin() {
            if (attempted) throw conflict();
            attempted = true;
        }

        AssistantCourseAnswers.Answer outcome() { return outcome; }

        void reject(AssistantCourseAnswers.Answer rejection) {
            outcome = rejection;
        }

        AssistantJournalActions.Outcome executeValidated(Supplier<AssistantJournalActions.Outcome> create) {
            reserve(true);
            if (!mongo.exists(activeClaim(claim), Receipt.class)) throw conflict();
            verifyCurrent(auth, preference, current);
            AssistantJournalActions.Outcome result;
            try {
                result = create.get();
            } catch (RuntimeException uncertain) {
                throw new UncertainWrite(uncertain);
            }
            try {
                outcome = settle(claim, result.answer());
            } catch (OptimisticLockingFailureException changed) {
                throw changed;
            } catch (DataAccessException uncertain) {
                throw new UncertainWrite(uncertain);
            }
            if (!result.answer().equals(outcome)) throw conflict();
            verifyCurrent(auth, preference, current);
            return result;
        }
    }

    private AssistantCourseAnswers.Answer evaluate(Authentication auth, AssistantProvider model, AssistantConversation row,
            UUID turnId, String text, Instant now, AssistantAccountSettings.Preference preference,
            BooleanSupplier current, WriteExecution execution) {
        var recent = recentTurns(row, preference, now);
        var topics = recent.stream()
                .flatMap(turn -> AssistantConversationalAnswers.recentTopics(turn.reply()).stream())
                .distinct().limit(4).toList();
        List<Message> context = new ArrayList<>();
        for (var turn : recent.reversed()) {
            // Source-backed replies contribute topics only. Every personal answer starts with a fresh lookup.
            if (turn.reply().parts().stream().anyMatch(part -> part.source() != null)) continue;
            String reply = turn.reply().parts().stream().map(AssistantCourseAnswers.Part::text)
                    .filter(part -> part != null && !part.isBlank()).findFirst().orElse("");
            if (reply.isEmpty()) continue;
            context.add(new UserMessage(redact(auth, turn.text())));
            context.add(new AssistantMessage(redact(auth, reply.substring(0, Math.min(reply.length(), 1800)))));
        }
        verifyCurrent(auth, preference, current);
        return new AssistantConversationalAnswers(model, faq.getIfAvailable(), reads).answer(
                auth.getName(), redact(auth, text), turnId, now, topics, context, true, current,
                journal, execution);
    }

    private static List<AssistantConversation.Turn> recentTurns(AssistantConversation row,
            AssistantAccountSettings.Preference preference, Instant now) {
        var recent = new ArrayList<AssistantConversation.Turn>();
        var today = now.atZone(AssistantJournalActions.ZONE).toLocalDate();
        for (var turn : row.turns().reversed()) {
            if (recent.size() >= MAX_CONTEXT_TURNS || Boolean.TRUE.equals(turn.journalAction())
                    || !Objects.equals(turn.generation(), preference.generation())
                    || !turn.at().plus(CONTEXT_AGE).isAfter(now)
                    || !today.equals(turn.at().atZone(AssistantJournalActions.ZONE).toLocalDate())) break;
            // Legacy canonical journal acknowledgements predate the journalAction marker.
            if (turn.reply().parts().stream().anyMatch(part -> part.source() == null && part.text() != null
                    && (part.text().startsWith("Đã ghi ") || part.text().equals(UNCERTAIN_WRITE)))) break;
            recent.add(turn);
        }
        return recent;
    }

    AssistantConversation createIfNew(Authentication auth, AssistantAccountSettings.Preference preference, UUID id) {
        requireCurrent(auth, preference);
        AssistantCourseController.ensureConversationIndexes(mongo);
        Instant now = clock.instant();
        return mongo.insert(new AssistantConversation(id.toString(), auth.getName(), null, now,
                now.plus(Duration.ofDays(7)), false, List.of()));
    }

    AssistantConversation getOwned(String owner, UUID id) {
        var row = mongo.findOne(owned(owner, id.toString()), AssistantConversation.class);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return row;
    }

    private Query owned(String owner, String id) {
        return Query.query(Criteria.where("owner").is(owner).and("expiresAt").gt(clock.instant())
                .and("id").is(id).and("deleting").ne(true));
    }

    static AssistantConversation.Turn replay(AssistantConversation row, UUID turnId, String text, String inputKey) {
        for (var turn : row.turns()) {
            if (turn.turnId().equals(turnId.toString())) {
                boolean matches = turn.inputKey() == null ? text == null || turn.text().equals(text)
                        : turn.inputKey().equals(inputKey);
                if (!matches) throw conflict();
                return turn;
            }
        }
        return null;
    }

    static void requireCapacity(AssistantConversation row) {
        if (row.turns().size() >= MAX_TURNS) throw conflict();
    }

    private static String redact(Authentication auth, String message) {
        String safe = message.replace(auth.getName(), "[account identifier omitted]");
        if (auth instanceof JwtAuthenticationToken token) {
            safe = safe.replace(token.getToken().getTokenValue(), "[access token omitted]");
        }
        return JWT.matcher(safe).replaceAll("[access token omitted]");
    }

    private boolean isCurrent(Authentication auth, AssistantAccountSettings.Preference preference) {
        if (auth instanceof JwtAuthenticationToken token && token.getToken().getExpiresAt() != null
                && !token.getToken().getExpiresAt().isAfter(clock.instant())) return false;
        return settings.isCurrent(auth.getName(), preference);
    }

    void requireCurrent(Authentication auth, AssistantAccountSettings.Preference preference) {
        if (!isCurrent(auth, preference)) throw new AssistantCourseController.AccountSettingChangedException();
    }

    private void verifyCurrent(Authentication auth, AssistantAccountSettings.Preference preference,
            BooleanSupplier current) {
        if (current.getAsBoolean()) return;
        requireCurrent(auth, preference);
        throw conflict();
    }

    private synchronized void ensureReceiptIndexes() {
        if (receiptIndexesReady) return;
        var indexes = mongo.indexOps(Receipt.class);
        indexes.createIndex(new Index().named("assistantTurnReceiptTtl")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
        indexes.createIndex(new Index().named("assistantTurnReceiptRevision")
                .on("conversationId", Sort.Direction.ASC).on("revision", Sort.Direction.ASC)
                .on("generation", Sort.Direction.ASC).unique());
        receiptIndexesReady = true;
    }

    void removeReplies(UUID conversationId) {
        mongo.updateMulti(Query.query(Criteria.where("conversationId").is(conversationId.toString())),
                new Update().unset("answer").set("discarded", true), Receipt.class);
    }

    static String textKey(String text) {
        return "text:" + digest(text.getBytes(StandardCharsets.UTF_8));
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static OptimisticLockingFailureException conflict() {
        return new OptimisticLockingFailureException("Assistant conversation or turn changed");
    }

    static final String UNCERTAIN_WRITE =
            "Chưa thể xác nhận thao tác ghi. Hãy kiểm tra nhật ký trước khi gửi yêu cầu mới.";

    static final class UncertainWrite extends DataAccessResourceFailureException {
        UncertainWrite(RuntimeException cause) { super(UNCERTAIN_WRITE, cause); }
    }

    @Document("assistant_turn_receipts")
    record Receipt(@Id String id, String conversationId, Long revision, String inputKey, long generation,
            Instant at, Instant claimedAt, Instant expiresAt, AssistantCourseAnswers.Answer answer, Boolean journalAction) {
        @org.springframework.data.annotation.PersistenceCreator
        Receipt {}

        Receipt(String id, String conversationId, Long revision, String inputKey, long generation,
                Instant at, Instant claimedAt, Instant expiresAt, AssistantCourseAnswers.Answer answer) {
            this(id, conversationId, revision, inputKey, generation, at, claimedAt, expiresAt, answer, false);
        }
    }
}
