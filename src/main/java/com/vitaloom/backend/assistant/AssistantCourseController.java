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

    AssistantCourseController(MongoTemplate mongo, AssistantCourseAnswers answers, Clock clock,
            @Qualifier("assistantReadyFaqSearch") ObjectProvider<AssistantFixtureSearch> readyFaqSearch) {
        this.mongo = mongo;
        this.answers = answers;
        this.clock = clock;
        this.readyFaqSearch = readyFaqSearch;
    }

    @GetMapping
    Page list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var query = owned(subject(auth)).with(Sort.by(Sort.Direction.DESC, "updatedAt", "id"))
                .skip((long) page * PAGE_SIZE).limit(PAGE_SIZE + 1);
        query.fields().exclude("turns");
        var rows = mongo.find(query, AssistantConversation.class);
        return new Page(rows.stream().limit(PAGE_SIZE).map(row -> new Summary(row.id(), row.updatedAt())).toList(),
                rows.size() > PAGE_SIZE);
    }

    @PostMapping
    ResponseEntity<View> create(Authentication auth, @Valid @RequestBody Acknowledgement request) {
        String owner = subject(auth);
        if (!request.accepted()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        mongo.indexOps(AssistantConversation.class).createIndex(new Index().named("assistantCourseExpiry")
                .on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
        mongo.indexOps(AssistantConversation.class).createIndex(new Index().named("assistantCourseOwnerUpdated")
                .on("owner", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC));
        Instant now = clock.instant();
        var row = new AssistantConversation(UUID.randomUUID().toString(), owner, null, now,
                now.plus(Duration.ofDays(7)), false, List.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(mongo.insert(row)));
    }

    @GetMapping("/{id}")
    View get(Authentication auth, @PathVariable UUID id) {
        return view(getOwned(subject(auth), id));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable UUID id) {
        if (mongo.remove(owned(subject(auth)).addCriteria(Criteria.where("id").is(id.toString())),
                AssistantConversation.class).getDeletedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permission")
    View permission(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Permission request) {
        var row = getOwned(subject(auth), id);
        // Even an idempotent permission request fences a concurrently calculated result.
        var history = row.personalAllowed() && !request.allowed() ? List.<AssistantConversation.Turn>of() : row.turns();
        return view(mongo.save(row.change(clock.instant(), request.allowed(), history)));
    }

    @PostMapping("/{id}/turns")
    View turn(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Send request) {
        String owner = subject(auth);
        var row = getOwned(owner, id);
        String text = request.text().strip();
        for (var turn : row.turns()) {
            if (turn.turnId().equals(request.turnId().toString())) {
                if (!turn.text().equals(text)) throw new ResponseStatusException(HttpStatus.CONFLICT);
                return view(row);
            }
        }
        if (row.turns().size() >= MAX_TURNS) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var now = clock.instant();
        var previous = row.turns().isEmpty() ? null : row.turns().getLast();
        var context = previous != null && previous.at().plus(Duration.ofMinutes(30)).isAfter(now)
                ? previous.reply().context() : null;
        var search = Boolean.TRUE.equals(request.geminiAccepted()) ? readyFaqSearch.getIfAvailable() : null;
        AssistantCourseAnswers.Answer answer;
        if (search == null) {
            answer = answers.answer(owner, text, row.personalAllowed(), context);
        } else {
            answer = answers.readyFaqLocalRoute(owner, text, row.personalAllowed(), context);
            if (answer == null) {
                try {
                    answer = search.answerReadyFaqContext(text, request.turnId(), 10);
                } catch (RuntimeException failure) {
                    answer = new AssistantCourseAnswers.Answer("DEPENDENCY_UNAVAILABLE",
                            List.of(new AssistantCourseAnswers.Part("DEPENDENCY_UNAVAILABLE",
                                    "FAQ Assistant is temporarily unavailable.", null)), null);
                }
            }
        }
        if (!row.expiresAt().isAfter(clock.instant())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var history = new java.util.ArrayList<>(row.turns());
        history.add(new AssistantConversation.Turn(request.turnId().toString(), text, now, answer));
        // Versioned save prevents recreation after deletion or publication with obsolete permission.
        return view(mongo.save(row.change(now, row.personalAllowed(), List.copyOf(history))));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> concurrentWrite() {
        return ProblemDetails.response(HttpStatus.CONFLICT, "ASSISTANT_CONVERSATION_CHANGED",
                "The conversation changed. Reload or retry the same turn.");
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

    private static View view(AssistantConversation row) {
        return new View(row.id(), row.updatedAt(), row.personalAllowed(), row.turns().stream()
                .map(turn -> new Message(turn.turnId(), turn.text(), turn.reply().outcome(), turn.reply().parts())).toList());
    }

    record Acknowledgement(@NotNull Boolean accepted) {}
    record Permission(@NotNull Boolean allowed) {}
    record Send(@NotNull UUID turnId, @NotBlank @Size(max = 300) String text, Boolean geminiAccepted) {}
    record Summary(String id, Instant updatedAt) {}
    record Page(List<Summary> items, boolean hasMore) {}
    record View(String id, Instant updatedAt, boolean personalAllowed, List<Message> turns) {}
    record Message(String turnId, String text, String outcome, List<AssistantCourseAnswers.Part> parts) {}
}
