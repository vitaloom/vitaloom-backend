package com.vitaloom.backend.sleep.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.repository.SleepSessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SleepMainReadQueryImplTest {
    private final SleepSessionRepository repository = mock(SleepSessionRepository.class);
    private final SleepMainReadQueryImpl query = new SleepMainReadQueryImpl(repository);
    private final LocalDate from = LocalDate.parse("2026-11-03");
    private final LocalDate to = LocalDate.parse("2026-11-09");

    @Test
    void mapsDurationAndRangeByStoredDayAndRequestsOnlyInclusiveAccountWindow() {
        when(repository.findMainByOwnerAndDayBetween("account-a", from.toString(), to.toString()))
                .thenReturn(List.of(session("range", "RANGE", "2026-11-03", 420),
                        session("duration", "DURATION", "2026-11-09", 451)));

        var result = query.readWindow("account-a", from, to);

        assertTrue(result.verified());
        assertEquals(List.of(from, to), result.records().stream().map(value -> value.day()).toList());
        assertEquals(List.of(420, 451), result.records().stream().map(value -> value.minutes()).toList());
        verify(repository).findMainByOwnerAndDayBetween("account-a", from.toString(), to.toString());
    }

    @Test
    void malformedFactMakesWindowUnverifiedAndDiscardsItsDay() {
        when(repository.findMainByOwnerAndDayBetween(any(), any(), any())).thenReturn(List.of(
                session("good", "DURATION", "2026-11-03", 420),
                session("bad", "DURATION", "2026-11-04", 1441)));

        var result = query.readWindow("account-a", from, to);

        assertFalse(result.verified());
        assertEquals(List.of(from), result.records().stream().map(value -> value.day()).toList());
    }

    @Test
    void duplicateMainDateIsUnverifiedAndExcluded() {
        when(repository.findMainByOwnerAndDayBetween(any(), any(), any())).thenReturn(List.of(
                session("first", "RANGE", "2026-11-03", 420),
                session("second", "DURATION", "2026-11-03", 450)));

        var result = query.readWindow("account-a", from, to);

        assertFalse(result.verified());
        assertTrue(result.records().isEmpty());
    }

    @Test
    void missingConfirmationOrUnknownMethodCannotVerifyWindow() {
        when(repository.findMainByOwnerAndDayBetween(any(), any(), any())).thenReturn(List.of(
                new SleepSession("unconfirmed", "account-a", "MAIN", "DURATION", 420,
                        "2026-11-03", null, null, null, null, null, null, null)));

        var result = query.readWindow("account-a", from, to);

        assertFalse(result.verified());
        assertTrue(result.records().isEmpty());
    }

    @Test
    void rangeWindowReturnsOnlyVerifiedRangeTimestampsAndKeepsDurationOutOfTimingData() {
        when(repository.findMainByOwnerAndDayBetween("account-a", from.toString(), to.toString()))
                .thenReturn(List.of(range("range", "2026-11-03"), session("duration", "DURATION", "2026-11-09", 451)));

        var result = query.readRangeWindow("account-a", from, to);

        assertTrue(result.verified());
        assertEquals(List.of("range", "duration"), result.records().stream().map(value -> value.sourceId()).toList());
        assertEquals(Instant.parse("2026-11-02T22:00:00Z"), result.records().getFirst().startAt());
        assertNull(result.records().getLast().timeZone());
        assertNull(result.records().getLast().startAt());
        verify(repository).findMainByOwnerAndDayBetween("account-a", from.toString(), to.toString());
    }

    @Test
    void malformedRangeTimestampMakesTimingWindowUnverified() {
        var malformed = new SleepSession("bad", "account-a", "MAIN", "RANGE", 420,
                "2026-11-03", null, Instant.parse("2026-11-09T16:00:00Z"), "UTC", null, null,
                Instant.parse("2026-11-03T22:00:00Z"), null);
        when(repository.findMainByOwnerAndDayBetween(any(), any(), any())).thenReturn(List.of(malformed));

        var result = query.readRangeWindow("account-a", from, to);

        assertFalse(result.verified());
        assertTrue(result.records().isEmpty());
    }

    @Test
    void fixedOffsetIsNotAcceptedAsAnIanaReferenceZone() {
        var invalidZone = new SleepSession("offset", "account-a", "MAIN", "RANGE", 420,
                "2026-11-03", null, Instant.parse("2026-11-09T16:00:00Z"), "+05:00", null, null,
                Instant.parse("2026-11-02T22:00:00Z"), Instant.parse("2026-11-03T05:00:00Z"));
        when(repository.findMainByOwnerAndDayBetween(any(), any(), any())).thenReturn(List.of(invalidZone));

        var result = query.readRangeWindow("account-a", from, to);

        assertFalse(result.verified());
        assertTrue(result.records().isEmpty());
    }

    private static SleepSession session(String id, String method, String day, int minutes) {
        return new SleepSession(id, "account-a", "MAIN", method, minutes, day, null,
                Instant.parse("2026-11-09T16:00:00Z"), null, null, null, null, null);
    }

    private static SleepSession range(String id, String day) {
        return new SleepSession(id, "account-a", "MAIN", "RANGE", 420, day, null,
                Instant.parse("2026-11-09T16:00:00Z"), "UTC", null, null,
                Instant.parse("2026-11-02T22:00:00Z"), Instant.parse("2026-11-03T05:00:00Z"));
    }
}
