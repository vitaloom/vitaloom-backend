package com.vitaloom.backend.assistant;

import com.vitaloom.backend.shared.web.ProblemDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Conditional(AssistantActivation.Course.class)
@RequestMapping("/api/assistant/mvp/conversations")
class AssistantCourseController {
    private static final int MAX_TURNS = 50;
    private static final int PAGE_SIZE = 20;
    private final MongoTemplate mongo;
    private final AssistantCourseAnswers answers;
    private final Clock clock;
    private final ObjectProvider<AssistantFixtureSearch> readyFaqSearch;
    private final AssistantAccountSettings accountSettings;
    private final ObjectProvider<AssistantProvider> provider;
    private final AssistantGeminiReadiness readiness;

    AssistantCourseController(MongoTemplate mongo, AssistantCourseAnswers answers, Clock clock,
            @Qualifier("assistantReadyFaqSearch") ObjectProvider<AssistantFixtureSearch> readyFaqSearch,
            AssistantAccountSettings accountSettings, ObjectProvider<AssistantProvider> provider,
            AssistantGeminiReadiness readiness) {
        this.mongo = mongo;
        this.answers = answers;
        this.clock = clock;
        this.readyFaqSearch = readyFaqSearch;
        this.accountSettings = accountSettings;
        this.provider = provider;
        this.readiness = readiness;
    }

    @GetMapping
    Page list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        var query = owned(owner).with(Sort.by(Sort.Direction.DESC, "updatedAt", "id"))
                .skip((long) page * PAGE_SIZE).limit(PAGE_SIZE + 1);
        query.fields().exclude("turns");
        var rows = mongo.find(query, AssistantConversation.class);
        requireCurrent(auth, preference);
        return new Page(rows.stream().limit(PAGE_SIZE).map(row -> new Summary(row.id(), row.updatedAt())).toList(),
                rows.size() > PAGE_SIZE);
    }

    @PostMapping
    ResponseEntity<View> create(Authentication auth, @RequestBody(required = false) Acknowledgement request) {
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        ensureConversationIndexes(mongo);
        Instant now = clock.instant();
        var row = new AssistantConversation(UUID.randomUUID().toString(), owner, null, now,
                now.plus(Duration.ofDays(7)), false, List.of());
        requireCurrent(auth, preference);
        var saved = mongo.insert(row);
        requireCurrent(auth, preference);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved, preference));
    }

    static void ensureConversationIndexes(MongoTemplate mongo) {
        mongo.indexOps(AssistantConversation.class).createIndex(new Index().named("assistantCourseExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
        mongo.indexOps(AssistantConversation.class).createIndex(new Index().named("assistantCourseOwnerUpdated")
                .on("owner", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC));
    }

    @GetMapping("/{id}")
    View get(Authentication auth, @PathVariable UUID id) {
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        var row = getOwned(owner, id);
        requireCurrent(auth, preference);
        return view(row, preference);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable UUID id) {
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        requireCurrent(auth, preference);
        if (mongo.remove(owned(owner).addCriteria(Criteria.where("id").is(id.toString())),
                AssistantConversation.class).getDeletedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permission")
    View permission(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Permission request) {
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        var row = getOwned(owner, id);
        requireCurrent(auth, preference);
        if (request.allowed() != preference.personalDataAllowed()) {
            throw AssistantAccountSettingsController.accountSettingRequired();
        }
        return view(row, preference);
    }

    @PostMapping("/{id}/turns")
    View turn(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Send request) {
        String owner = subject(auth);
        var preference = accountSettings.requireEnabled(owner);
        var row = getOwned(owner, id);
        String text = request.text().strip();
        for (var turn : row.turns()) {
            if (turn.turnId().equals(request.turnId().toString())) {
                if (!turn.text().equals(text)) throw new ResponseStatusException(HttpStatus.CONFLICT);
                requireCurrent(auth, preference);
                return view(row, preference);
            }
        }
        if (row.turns().size() >= MAX_TURNS) throw new ResponseStatusException(HttpStatus.CONFLICT);

        readiness.requireReady();
        var model = provider.getIfAvailable();
        if (model == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var now = clock.instant();
        var topics = row.turns().reversed().stream()
                .filter(turn -> turn.at().plus(Duration.ofMinutes(30)).isAfter(now))
                .flatMap(turn -> AssistantConversationalAnswers.recentTopics(turn.reply()).stream())
                .distinct().limit(4).toList();
        BooleanSupplier current = () -> isCurrent(auth, preference)
                && mongo.exists(owned(owner).addCriteria(Criteria.where("id").is(id.toString())
                        .and("revision").is(row.revision())), AssistantConversation.class);
        AssistantCourseAnswers.Answer answer;
        try {
            requireCurrent(auth, preference);
            String modelText = text.replace(owner, "[account identifier omitted]");
            if (auth instanceof JwtAuthenticationToken token) {
                modelText = modelText.replace(token.getToken().getTokenValue(), "[access token omitted]");
            }
            answer = new AssistantConversationalAnswers(model, readyFaqSearch.getIfAvailable(), answers)
                    .answer(owner, modelText, request.turnId(), topics, true, current, current);
        } catch (AssistantGeminiTurn.AccessChangedException
                | AssistantPersonalDataTools.PersonalDataAccessDeniedException changed) {
            requireCurrent(auth, preference);
            throw new OptimisticLockingFailureException("Assistant conversation changed");
        }
        if (!current.getAsBoolean()) {
            requireCurrent(auth, preference);
            throw new OptimisticLockingFailureException("Assistant conversation changed");
        }
        var history = new ArrayList<>(row.turns());
        history.add(new AssistantConversation.Turn(request.turnId().toString(), text, now, answer));
        var saved = mongo.save(row.change(clock.instant(), true, List.copyOf(history)));
        requireCurrent(auth, preference);
        return view(saved, preference);
    }

    private boolean isCurrent(Authentication auth, AssistantAccountSettings.Preference preference) {
        if (auth instanceof JwtAuthenticationToken token && token.getToken().getExpiresAt() != null
                && !token.getToken().getExpiresAt().isAfter(clock.instant())) return false;
        return accountSettings.isCurrent(subject(auth), preference);
    }

    private void requireCurrent(Authentication auth, AssistantAccountSettings.Preference preference) {
        if (!isCurrent(auth, preference)) throw new AccountSettingChangedException();
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> concurrentWrite() {
        return ProblemDetails.response(HttpStatus.CONFLICT, "ASSISTANT_CONVERSATION_CHANGED",
                "The conversation changed. Reload or retry the same turn.");
    }

    @ExceptionHandler(AccountSettingChangedException.class)
    ResponseEntity<?> accountSettingChanged() {
        return ProblemDetails.response(HttpStatus.CONFLICT, "ASSISTANT_ACCOUNT_SETTING_CHANGED",
                "Assistant personal-data access changed while the turn was processing.");
    }

    @ExceptionHandler(AssistantAccountSettingsController.AssistantAccountSettingRequiredException.class)
    ResponseEntity<?> permissionMustBeChangedInSettings() {
        return ProblemDetails.response(HttpStatus.CONFLICT, "ASSISTANT_ACCOUNT_SETTING_REQUIRED",
                "Change personal-data access in Assistant account settings.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> storeUnavailable() {
        return ProblemDetails.response(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE",
                "The Assistant is temporarily unavailable.");
    }

    private String subject(Authentication auth) {
        return auth.getName();
    }

    private Query owned(String owner) {
        return Query.query(Criteria.where("owner").is(owner).and("expiresAt").gt(clock.instant()));
    }

    private AssistantConversation getOwned(String owner, UUID id) {
        var row = mongo.findOne(owned(owner).addCriteria(Criteria.where("id").is(id.toString())),
                AssistantConversation.class);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return row;
    }

    static View view(AssistantConversation row) {
        return view(row, row.personalAllowed());
    }

    static View view(AssistantConversation row, AssistantAccountSettings.Preference preference) {
        return view(row, preference.personalDataAllowed());
    }

    private static View view(AssistantConversation row, boolean personalAllowed) {
        return new View(row.id(), row.updatedAt(), personalAllowed, row.turns().stream()
                .map(turn -> new Message(turn.turnId(), turn.text(), turn.reply().outcome(), turn.reply().parts())).toList());
    }

    record Acknowledgement(@NotNull Boolean accepted) {}
    record Permission(@NotNull Boolean allowed) {}
    record Send(@NotNull UUID turnId, @NotBlank @Size(max = 300) String text, Boolean geminiAccepted) {}
    record Summary(String id, Instant updatedAt) {}
    record Page(List<Summary> items, boolean hasMore) {}
    record View(String id, Instant updatedAt, boolean personalAllowed, List<Message> turns) {}
    record Message(String turnId, String text, String outcome, List<AssistantCourseAnswers.Part> parts) {}

    static final class AccountSettingChangedException extends RuntimeException {}
}
