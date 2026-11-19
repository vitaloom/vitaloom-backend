package com.vitaloom.backend.assistant;

import com.vitaloom.backend.shared.web.ProblemDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private static final int PAGE_SIZE = 20;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final AssistantAccountSettings accountSettings;
    private final AssistantConversationTurns turns;

    AssistantCourseController(MongoTemplate mongo, Clock clock, AssistantAccountSettings accountSettings,
            AssistantConversationTurns turns) {
        this.mongo = mongo;
        this.clock = clock;
        this.accountSettings = accountSettings;
        this.turns = turns;
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
        var saved = turns.createIfNew(auth, preference, UUID.randomUUID());
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
        var target = ownedIncludingDeleted(owner).addCriteria(Criteria.where("id").is(id.toString()));
        if (mongo.updateFirst(target, new Update().set("deleting", true).unset("turns").inc("revision", 1),
                AssistantConversation.class).getMatchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        turns.removeReplies(id);
        mongo.remove(target, AssistantConversation.class);
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
        String text = request.text().strip();
        return turns.submit(auth, preference, id, request.turnId(), text,
                AssistantConversationTurns.textKey(text), clock.instant());
    }

    private void requireCurrent(Authentication auth, AssistantAccountSettings.Preference preference) {
        turns.requireCurrent(auth, preference);
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
        return ownedIncludingDeleted(owner).addCriteria(Criteria.where("deleting").ne(true));
    }

    private Query ownedIncludingDeleted(String owner) {
        return Query.query(Criteria.where("owner").is(owner).and("expiresAt").gt(clock.instant()));
    }

    private AssistantConversation getOwned(String owner, UUID id) {
        return turns.getOwned(owner, id);
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
