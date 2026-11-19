package com.vitaloom.backend.sleep.goal;

import com.vitaloom.backend.sleep.exception.SleepFailure;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class SleepGoalRepository {
    private final MongoTemplate mongo;

    public SleepGoalRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public SleepGoalHistory read(String accountId) {
        SleepGoalHistory history = mongo.findById(accountId, SleepGoalHistory.class);
        if (history == null) return new SleepGoalHistory(accountId, 0, List.of());
        validate(history);
        return history;
    }

    public SleepGoalHistory save(String accountId, long expectedRevision, LocalDate effectiveDay,
            Integer minutes, boolean removed) {
        SleepGoalHistory current = read(accountId);
        if (current.revision() != expectedRevision) throw conflict();
        List<SleepGoalHistory.Snapshot> snapshots = new ArrayList<>(current.snapshots());
        snapshots.removeIf(snapshot -> effectiveDay.toString().equals(snapshot.effectiveDay()));
        snapshots.add(new SleepGoalHistory.Snapshot(effectiveDay.toString(), minutes, removed));
        snapshots.sort(Comparator.comparing(SleepGoalHistory.Snapshot::effectiveDay));

        if (expectedRevision == 0 && current.snapshots().isEmpty()) {
            try {
                return mongo.insert(new SleepGoalHistory(accountId, 1, snapshots));
            } catch (DuplicateKeyException racedCreation) {
                throw conflict();
            }
        }
        Query query = Query.query(Criteria.where("_id").is(accountId).and("revision").is(expectedRevision));
        var result = mongo.updateFirst(query, new Update().set("snapshots", snapshots).inc("revision", 1),
                SleepGoalHistory.class);
        if (result.getModifiedCount() != 1) throw conflict();
        return new SleepGoalHistory(accountId, expectedRevision + 1, snapshots);
    }

    public Optional<SleepGoalHistory.Snapshot> effective(String accountId, LocalDate day) {
        return read(accountId).snapshots().stream()
                .filter(snapshot -> snapshot.day().compareTo(day) <= 0)
                .max(Comparator.comparing(SleepGoalHistory.Snapshot::effectiveDay));
    }

    private static void validate(SleepGoalHistory history) {
        if (history.revision() < 1 || history.revision() > 9_007_199_254_740_991L
                || history.snapshots() == null || history.snapshots().isEmpty()) throw unverified();
        Set<String> days = new HashSet<>();
        for (SleepGoalHistory.Snapshot snapshot : history.snapshots()) {
            if (snapshot == null || snapshot.effectiveDay() == null
                    || !snapshot.effectiveDay().matches("\\d{4}-\\d{2}-\\d{2}")
                    || !days.add(snapshot.effectiveDay())
                    || snapshot.removed() && snapshot.minutes() != null
                    || !snapshot.removed() && (snapshot.minutes() == null || snapshot.minutes() < 60
                    || snapshot.minutes() > 1440 || snapshot.minutes() % 15 != 0)) throw unverified();
            try { snapshot.day(); } catch (RuntimeException invalidDate) { throw unverified(); }
        }
    }

    private static SleepFailure unverified() {
        return new SleepFailure(500, "SLEEP_GOAL_UNVERIFIED", "Sleep Goal history could not be verified.");
    }

    private static SleepFailure conflict() {
        return new SleepFailure(409, "SLEEP_GOAL_CONFLICT", "Sleep Goal changed. Reload before saving.");
    }
}
