package com.vitaloom.backend.sleep.service.impl;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.repository.SleepSessionRepository;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SleepJournalServiceImpl implements SleepJournalService {
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
        return sessions.findByOwner(owner, from, to).stream().map(SessionResponse::from).toList();
    }

    @Override
    public SessionResponse detail(String owner, String id) {
        return SessionResponse.from(requireSession(owner, id));
    }

    @Override
    public SessionResponse create(String owner, SessionWrite request) {
        SleepSession candidate = SleepSessionValidator.validate(
                request, null, UUID.randomUUID().toString(), owner, clock.instant());
        rejectOverlap(owner, candidate);
        return SessionResponse.from(sessions.save(candidate));
    }

    @Override
    public SessionResponse update(String owner, String id, SessionWrite request) {
        SleepSession existing = requireSession(owner, id);
        SleepSession candidate = SleepSessionValidator.validate(
                request, existing, existing.getId(), owner, clock.instant());
        rejectOverlap(owner, candidate);
        return SessionResponse.from(sessions.save(candidate));
    }

    @Override
    public void delete(String owner, String id) {
        sessions.delete(requireSession(owner, id));
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
