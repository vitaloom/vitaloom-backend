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
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
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
    private static final int MAX_PENDING_TURNS = 3;
    private static final int MAX_TURNS = 50;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final AssistantCourseAnswers reads;
    private final ObjectProvider<AssistantFixtureSearch> faq;
    private final ObjectProvider<AssistantProvider> provider;
    private final AssistantGeminiReadiness readiness;
    private final AssistantAccountSettings settings;
    private final AssistantVoiceActions journal;
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
        this.journal = new AssistantVoiceActions(water, sleep);
    }

    AssistantCourseController.View submit(Authentication auth, AssistantAccountSettings.Preference preference,
            UUID conversationId, UUID turnId, String text, String inputKey, Instant receivedAt) {
        String owner = auth.getName();
        requireCurrent(auth, preference);
        var row = getOwned(owner, conversationId);
        var existing = replay(row, turnId, text, inputKey);
        if (existing != null) {
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
        if (receipt != null) {
            if (!receipt.conversationId().equals(row.id()) || !receipt.inputKey().equals(inputKey)
                    || receipt.generation() != preference.generation()) {
                throw conflict();
            }
            answer = recoverAnswer(receipt);
            at = receipt.at();
        } else {
            readiness.requireReady();
            var model = provider.getIfAvailable();
            if (model == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            var pending = pendingTurns(row, preference, receivedAt);
            var evaluation = evaluate(auth, preference, model, row, turnId, text, receivedAt, pending, current);
            verifyCurrent(auth, preference, current);

            // Reserve both the logical turn and its conversation revision before allowing a side effect.
            // A completed receipt can be republished after a failed chat save. An uncertain write is never retried.
            ensureReceiptIndexes();
            try {
                mongo.insert(new Receipt(receiptId, row.id(), row.revision(), inputKey, preference.generation(),
                        receivedAt, clock.instant(), row.expiresAt(), null));
            } catch (DuplicateKeyException duplicate) {
                throw conflict();
            }
            answer = evaluation.answer();
            verifyCurrent(auth, preference, current);
            if (evaluation.action() != null) {
                var evidence = new ArrayList<String>();
                for (var turn : pending) evidence.add(redact(auth, turn.text()));
                evidence.add(redact(auth, text));
                answer = journal.apply(owner, evidence, receivedAt, evaluation.action());
            }
            if (!completeReceipt(receiptId, answer)) throw conflict();
        }

        verifyCurrent(auth, preference, current);
        var history = new ArrayList<>(row.turns());
        history.add(new AssistantConversation.Turn(turnId.toString(), text, at, answer, inputKey,
                preference.generation()));
        var saved = mongo.save(row.change(clock.instant(), true, List.copyOf(history)));
        requireCurrent(auth, preference);
        return AssistantCourseController.view(saved, preference);
    }

    private AssistantCourseAnswers.Answer recoverAnswer(Receipt receipt) {
        if (receipt.answer() != null) return receipt.answer();
        if (receipt.claimedAt().plus(WRITE_SETTLEMENT_TIME).isAfter(clock.instant())) throw conflict();
        var uncertain = AssistantVoiceActions.answer("DEPENDENCY_UNAVAILABLE",
                "Chưa thể xác nhận thao tác ghi. Hãy kiểm tra nhật ký trước khi gửi yêu cầu mới.");
        if (!completeReceipt(receipt.id(), uncertain)) throw conflict();
        return uncertain;
    }

    private boolean completeReceipt(String receiptId, AssistantCourseAnswers.Answer answer) {
        return mongo.updateFirst(Query.query(Criteria.where("id").is(receiptId).and("answer").is(null)
                        .and("discarded").ne(true)), new Update().set("answer", answer), Receipt.class)
                .getMatchedCount() == 1;
    }

    private AssistantConversationalAnswers.Evaluation evaluate(Authentication auth,
            AssistantAccountSettings.Preference preference, AssistantProvider model, AssistantConversation row,
            UUID turnId, String text, Instant now, List<AssistantConversation.Turn> pending, BooleanSupplier current) {
        var topics = row.turns().reversed().stream().filter(turn -> turn.at().plus(CONTEXT_AGE).isAfter(now))
                .flatMap(turn -> AssistantConversationalAnswers.recentTopics(turn.reply()).stream())
                .distinct().limit(4).toList();
        List<Message> context = new ArrayList<>();
        for (var turn : pending) {
            context.add(new UserMessage(redact(auth, turn.text())));
            context.add(new AssistantMessage(redact(auth, turn.reply().parts().getFirst().text())));
        }
        try {
            verifyCurrent(auth, preference, current);
            return new AssistantConversationalAnswers(model, faq.getIfAvailable(), reads)
                    .evaluate(auth.getName(), redact(auth, text), turnId, topics, context, true, current, current);
        } catch (AssistantGeminiTurn.AccessChangedException
                | AssistantPersonalDataTools.PersonalDataAccessDeniedException changed) {
            requireCurrent(auth, preference);
            throw conflict();
        }
    }

    private static List<AssistantConversation.Turn> pendingTurns(AssistantConversation row,
            AssistantAccountSettings.Preference preference, Instant now) {
        var pending = new ArrayList<AssistantConversation.Turn>();
        var today = now.atZone(AssistantVoiceActions.ZONE).toLocalDate();
        for (var turn : row.turns().reversed()) {
            if (pending.size() >= MAX_PENDING_TURNS || !"WRITE_CLARIFICATION".equals(turn.reply().outcome())
                    || !Objects.equals(turn.generation(), preference.generation())
                    || !turn.at().plus(CONTEXT_AGE).isAfter(now)
                    || !today.equals(turn.at().atZone(AssistantVoiceActions.ZONE).toLocalDate())) break;
            pending.add(turn);
        }
        Collections.reverse(pending);
        return pending;
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

    @Document("assistant_turn_receipts")
    record Receipt(@Id String id, String conversationId, Long revision, String inputKey, long generation,
            Instant at, Instant claimedAt, Instant expiresAt, AssistantCourseAnswers.Answer answer) {}
}
