package com.vitaloom.backend.sleep.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.model.SleepSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SleepSessionValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Instant DST_GAP_NOW = Instant.parse("2026-03-08T12:00:00Z");
    private static final Instant DST_OVERLAP_NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void acceptsInclusiveDurationBoundsAndAllFeelings() {
        for (int minutes : new int[] {1, 1440}) {
            for (String feeling : new String[] {"COMFORTABLE", "NORMAL", "TIRED", null}) {
                SleepSession saved = validate(duration(minutes, "2026-11-13", feeling), null);
                assertEquals(minutes, saved.getMinutes());
                assertEquals("2026-11-13", saved.getDay());
                assertNull(saved.getStartAt());
                assertNull(saved.getEndAt());
            }
        }
    }

    @Test
    void rejectsInvalidDurationsAndFutureReferenceDay() {
        for (String minutes : new String[] {"0", "-1", "1441", "1.5", "\"2\"", "null"}) {
            SessionWrite request = durationNode(minutes, "2026-11-13", null);
            assertEquals("SLEEP_VALIDATION", assertThrows(SleepFailure.class,
                    () -> validate(request, null)).code());
        }
        assertThrows(SleepFailure.class,
                () -> validate(duration(1, "2026-11-14", null), null));
    }

    @Test
    void acceptsOnlyMainSleepAndRejectsPreviousWeekWithLockedError() {
        SleepFailure nap = assertThrows(SleepFailure.class,
                () -> validate(new SessionWrite("NAP", "DURATION", null, null, null, null,
                        "2026-11-13", json("60")), null));
        assertEquals("SLEEP_VALIDATION", nap.code());

        SleepFailure previousWeek = assertThrows(SleepFailure.class,
                () -> validate(duration(60, "2026-11-08", null), null));
        assertEquals(409, previousWeek.status());
        assertEquals("SLEEP_WEEK_LOCKED", previousWeek.code());
    }

    @Test
    void allowsMondayAndTodayButRejectsFutureSaturday() {
        assertEquals("2026-11-09", validate(duration(60, "2026-11-09", null), null).getDay());
        assertEquals("2026-11-13", validate(duration(60, "2026-11-13", null), null).getDay());
        SleepFailure future = assertThrows(SleepFailure.class,
                () -> validate(duration(60, "2026-11-14", null), null));
        assertEquals("SLEEP_VALIDATION", future.code());
    }

    @Test
    void allowsRangeEndpointsThatMeetExactlyAndUsesElapsedTime() {
        SessionWrite request = range("UTC", "2026-11-12", "23", "30", null,
                "2026-11-13", "00", "30", null);
        SleepSession session = validate(request, null);
        assertEquals(60, session.getMinutes());
        assertEquals("2026-11-13", session.getDay());
    }

    @Test
    void rejectsNonexistentAndAmbiguousLocalTimesWithoutExplicitChoice() {
        SessionWrite gap = range("America/New_York", "2026-03-08", "02", "30", null,
                "2026-03-08", "03", "30", null);
        assertThrows(SleepFailure.class, () -> validate(gap, null, DST_GAP_NOW));

        SessionWrite ambiguous = range("America/New_York", "2026-11-01", "01", "30", null,
                "2026-11-01", "02", "30", null);
        assertThrows(SleepFailure.class, () -> validate(ambiguous, null, DST_OVERLAP_NOW));
    }

    @Test
    void honorsExplicitDstOffsetAndRejectsInvalidOffset() {
        SessionWrite request = range("America/New_York", "2026-11-01", "01", "30", "-240",
                "2026-11-01", "02", "30", "-300");
        assertEquals(120, validate(request, null, DST_OVERLAP_NOW).getMinutes());

        SessionWrite invalidOffset = range("America/New_York", "2026-11-01", "01", "30", "0",
                "2026-11-01", "02", "30", "-300");
        assertThrows(SleepFailure.class, () -> validate(invalidOffset, null, DST_OVERLAP_NOW));
    }

    @Test
    void preservesRangeTimezoneOnEditAndClearsRangeFieldsWhenConvertedToDuration() {
        SleepSession existing = validate(range("UTC", "2026-11-12", "22", "00", null,
                "2026-11-12", "23", "00", null), null);
        SessionWrite changedZone = range("Etc/GMT", "2026-11-12", "22", "00", null,
                "2026-11-12", "23", "00", null);
        assertThrows(SleepFailure.class, () -> validate(changedZone, existing));

        SleepSession converted = validate(duration(45, "2026-11-12", null), existing);
        assertNull(converted.getTimeZone());
        assertNull(converted.getStart());
        assertNull(converted.getEnd());
        assertNull(converted.getStartAt());
        assertNull(converted.getEndAt());
    }

    private static SleepSession validate(SessionWrite request, SleepSession existing) {
        return SleepSessionValidator.validate(request, existing, "session", "account", NOW);
    }

    private static SleepSession validate(SessionWrite request, SleepSession existing, Instant now) {
        return SleepSessionValidator.validate(request, existing, "session", "account", now);
    }

    private static SessionWrite duration(int minutes, String endDay, String feeling) {
        return durationNode(Integer.toString(minutes), endDay, feeling);
    }

    private static SessionWrite durationNode(String minutes, String endDay, String feeling) {
        return new SessionWrite("MAIN", "DURATION", feeling, null, null, null, endDay, json(minutes));
    }

    private static SessionWrite range(String zone, String startDay, String startHour, String startMinute,
                                      String startOffset, String endDay, String endHour, String endMinute,
                                      String endOffset) {
        return new SessionWrite("MAIN", "RANGE", null, zone,
                civil(startDay, startHour, startMinute, startOffset),
                civil(endDay, endHour, endMinute, endOffset), null, null);
    }

    private static SessionWrite.CivilTime civil(String day, String hour, String minute, String offset) {
        return new SessionWrite.CivilTime(day, hour, minute, offset == null ? null : json(offset));
    }

    private static tools.jackson.databind.JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
