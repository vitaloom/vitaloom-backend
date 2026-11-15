package com.vitaloom.backend.sleep.goal;

import com.vitaloom.backend.sleep.exception.SleepFailure;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SleepGoalService {
    private final SleepGoalRepository repository;
    private final Clock clock;
    private final ZoneId timeZone;

    public SleepGoalService(SleepGoalRepository repository, Clock clock, ZoneId timeZone) {
        this.repository = repository;
        this.clock = clock;
        this.timeZone = timeZone;
    }

    public SleepGoalResponse get(String accountId, LocalDate day) {
        LocalDate today = today();
        LocalDate requestedDay = day == null ? today : day;
        if (requestedDay.isAfter(today)) throw invalid("day", "day cannot be after today.");
        SleepGoalHistory history = repository.read(accountId);
        Optional<SleepGoalHistory.Snapshot> effective = history.snapshots().stream()
                .filter(snapshot -> snapshot.day().compareTo(requestedDay) <= 0)
                .max(java.util.Comparator.comparing(SleepGoalHistory.Snapshot::effectiveDay));
        SleepGoalHistory.Snapshot snapshot = effective.orElse(null);
        if (snapshot != null && snapshot.removed()) snapshot = null;
        return new SleepGoalResponse(snapshot == null ? null : snapshot.minutes(), history.revision(),
                effective.map(SleepGoalHistory.Snapshot::effectiveDay).orElse(null), true);
    }

    public SleepGoalResponse set(String accountId, Integer minutes, Long revision) {
        if (minutes == null || minutes < 60 || minutes > 1440 || minutes % 15 != 0) {
            throw invalid("minutes", "minutes must be from 60 to 1440 in 15-minute increments.");
        }
        return change(accountId, minutes, revision, false);
    }

    public SleepGoalResponse remove(String accountId, Long revision) {
        return change(accountId, null, revision, true);
    }

    public Optional<Integer> effectiveMinutes(String accountId, LocalDate day) {
        return repository.effective(accountId, day)
                .filter(snapshot -> !snapshot.removed())
                .map(SleepGoalHistory.Snapshot::minutes);
    }

    private SleepGoalResponse change(String accountId, Integer minutes, Long revision, boolean removed) {
        if (revision == null || revision < 0) throw invalid("revision", "revision must be a non-negative integer.");
        LocalDate effectiveDay = today();
        SleepGoalHistory updated = repository.save(accountId, revision, effectiveDay, minutes, removed);
        return new SleepGoalResponse(removed ? null : minutes, updated.revision(), effectiveDay.toString(), true);
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(timeZone)); }

    private static SleepFailure invalid(String field, String message) {
        return new SleepFailure(400, "INVALID_SLEEP_GOAL", message, field);
    }
}
