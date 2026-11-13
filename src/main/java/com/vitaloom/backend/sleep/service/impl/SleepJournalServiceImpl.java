package com.vitaloom.backend.sleep.service.impl;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.repository.SleepSessionRepository;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class SleepJournalServiceImpl implements SleepJournalService {
    private static final ZoneId MUTATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Object[] OWNER_LOCKS = createOwnerLocks();
    private final SleepSessionRepository sessions;
    private final Clock clock;

    public SleepJournalServiceImpl(SleepSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public List<SessionResponse> list(String owner, String from, String to) {
        validateDay(from);
        validateDay(to);
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw invalid("The from day must be on or before the to day.");
        }
        synchronized (ownerLock(owner)) {
            String latestDay = sessions.latestMainDay(owner);
            LocalDate today = LocalDate.now(clock.withZone(MUTATION_ZONE));
            return sessions.findByOwner(owner, from, to).stream()
                    .map(session -> SessionResponse.from(session, isEditable(session, latestDay, today))).toList();
        }
    }

    @Override
    public SessionResponse detail(String owner, String id) {
        synchronized (ownerLock(owner)) {
            SleepSession session = requireSession(owner, id);
            String latestDay = sessions.latestMainDay(owner);
            LocalDate today = LocalDate.now(clock.withZone(MUTATION_ZONE));
            return SessionResponse.from(session, isEditable(session, latestDay, today));
        }
    }

    @Override
    public SessionResponse create(String owner, SessionWrite request) {
        synchronized (ownerLock(owner)) {
            SleepSession candidate = SleepSessionValidator.validate(
                    request, null, UUID.randomUUID().toString(), owner, clock.instant());
            candidate = candidate.withConfirmedAt(persistedNow());
            rejectDayAlreadyRecorded(owner, candidate, null);
            rejectOverlap(owner, candidate);
            try {
                SleepSession saved = sessions.insert(candidate);
                return SessionResponse.from(saved, isEditable(saved, sessions.latestMainDay(owner),
                        LocalDate.now(clock.withZone(MUTATION_ZONE))));
            } catch (DuplicateKeyException exception) {
                throw dayAlreadyRecorded();
            }
        }
    }

    @Override
    public SessionResponse update(String owner, String id, SessionWrite request, Instant expectedConfirmedAt) {
        synchronized (ownerLock(owner)) {
            SleepSession existing = requireSession(owner, id);
            rejectStale(existing, expectedConfirmedAt);
            requireCurrentWeek(existing, LocalDate.now(clock.withZone(MUTATION_ZONE)));
            String latestDay = sessions.latestMainDay(owner);
            if (!isEditable(existing, latestDay, LocalDate.now(clock.withZone(MUTATION_ZONE)))) {
                throw notLatest();
            }
            SleepSession candidate = SleepSessionValidator.validate(
                    request, existing, existing.getId(), owner, clock.instant())
                    .withConfirmedAt(nextVersion(existing));
            rejectDayAlreadyRecorded(owner, candidate, existing.getId());
            rejectOverlap(owner, candidate);
            try {
                if (!sessions.replaceIfConfirmedAt(candidate, existing.getConfirmedAt())) throw stale();
                return SessionResponse.from(candidate, isEditable(candidate, sessions.latestMainDay(owner),
                        LocalDate.now(clock.withZone(MUTATION_ZONE))));
            } catch (DuplicateKeyException exception) {
                throw dayAlreadyRecorded();
            }
        }
    }

    @Override
    public void delete(String owner, String id, Instant expectedConfirmedAt) {
        synchronized (ownerLock(owner)) {
            SleepSession session = requireSession(owner, id);
            rejectStale(session, expectedConfirmedAt);
            requireCurrentWeek(session, LocalDate.now(clock.withZone(MUTATION_ZONE)));
            String latestDay = sessions.latestMainDay(owner);
            if (!isEditable(session, latestDay, LocalDate.now(clock.withZone(MUTATION_ZONE)))) {
                throw notLatest();
            }
            if (!sessions.deleteIfConfirmedAt(session, session.getConfirmedAt())) throw stale();
        }
    }

    private void rejectDayAlreadyRecorded(String owner, SleepSession candidate, String excludedId) {
        if (sessions.existsMainOnDay(owner, candidate.getDay(), excludedId)) throw dayAlreadyRecorded();
    }

    private static boolean isEditable(SleepSession session, String latestDay, LocalDate today) {
        if (!"MAIN".equals(session.getSleepType()) || latestDay == null || !latestDay.equals(session.getDay())) {
            return false;
        }
        return isCurrentWeek(session, today);
    }

    private static boolean isCurrentWeek(SleepSession session, LocalDate today) {
        LocalDate day = LocalDate.parse(session.getDay());
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return !day.isBefore(monday) && !day.isAfter(today);
    }

    private static void requireCurrentWeek(SleepSession session, LocalDate today) {
        if (!isCurrentWeek(session, today)) {
            throw new SleepFailure(409, "SLEEP_WEEK_LOCKED", "Sleep records from completed weeks are read-only.");
        }
    }

    private static Object ownerLock(String owner) {
        return OWNER_LOCKS[Math.floorMod(owner.hashCode(), OWNER_LOCKS.length)];
    }

    private static Object[] createOwnerLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static SleepFailure dayAlreadyRecorded() {
        return new SleepFailure(409, "SLEEP_DAY_ALREADY_RECORDED", "A Main Sleep is already recorded for this day.");
    }

    private static SleepFailure notLatest() {
        return new SleepFailure(409, "SLEEP_RECORD_NOT_LATEST", "Only the latest current-week Main Sleep can be changed.");
    }

    private static void rejectStale(SleepSession session, Instant expectedConfirmedAt) {
        if (expectedConfirmedAt != null && !expectedConfirmedAt.equals(session.getConfirmedAt())) throw stale();
    }

    private static SleepFailure stale() {
        return new SleepFailure(409, "SLEEP_STALE", "This sleep record has changed. Reload the current record before editing it.");
    }

    private Instant persistedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant nextVersion(SleepSession existing) {
        Instant now = persistedNow();
        Instant next = existing.getConfirmedAt().plusMillis(1);
        return now.isAfter(existing.getConfirmedAt()) ? now : next;
    }

    private void rejectOverlap(String owner, SleepSession candidate) {
        if (candidate.getStartAt() != null && sessions.existsRangeOverlap(owner, candidate)) {
            throw new SleepFailure(409, "SLEEP_OVERLAP", "This range overlaps another sleep session.");
        }
    }

    private SleepSession requireSession(String owner, String id) {
        SleepSession session = sessions.findByOwnerAndId(owner, id);
        if (session == null) throw new SleepFailure(404, "SLEEP_NOT_FOUND", "Sleep session was not found.");
        return session;
    }

    private static void validateDay(String day) {
        if (day == null) return;
        try {
            if (day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && LocalDate.parse(day).toString().equals(day)) return;
        } catch (DateTimeParseException ignored) {
            // Invalid date is reported as a client input error below.
        }
        throw invalid("Use a valid ISO statistical day.");
    }

    private static SleepFailure invalid(String detail) {
        return new SleepFailure(400, "VALIDATION_ERROR", detail);
    }
}
