package com.vitaloom.backend.sleep.service.impl;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.model.SleepSession;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class SleepSessionValidator {
    private static final String VALIDATION_CODE = "SLEEP_VALIDATION";
    private static final ZoneId DURATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private SleepSessionValidator() {}

    public static SleepSession validate(SessionWrite request, SleepSession existing, String id,
                                        String owner, Instant now) {
        if (request == null) throw invalid("request", "A session is required.");
        String type = enumValue(request.sleepType(), "sleepType", "MAIN", "NAP");
        String method = enumValue(request.method(), "method", "RANGE", "DURATION");
        String feeling = optionalEnum(request.feeling(), "feeling", "COMFORTABLE", "NORMAL", "TIRED");
        if (now == null || owner == null || owner.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Validator context is incomplete.");
        }

        if ("DURATION".equals(method)) {
            rejectRangeFields(request);
            LocalDate endDay = parseDay(request.endDay(), "endDay");
            if (endDay.isAfter(now.atZone(DURATION_ZONE).toLocalDate())) {
                throw invalid("endDay", "The end day cannot be in the future.");
            }
            int minutes = wholeMinutes(request.minutes());
            return new SleepSession(id, owner, type, method, minutes, endDay.toString(), feeling,
                    now, null, null, null, null, null);
        }

        if (request.endDay() != null || request.minutes() != null) {
            throw invalid("method", "Range sessions cannot contain duration-only fields.");
        }
        String zoneText = required(request.timeZone(), "timeZone");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneText);
            if (!ZoneId.getAvailableZoneIds().contains(zoneText)) throw new DateTimeException("An IANA timezone is required.");
        } catch (DateTimeException exception) {
            throw invalid("timeZone", "Choose a valid IANA timezone.");
        }
        if (existing != null && "RANGE".equals(existing.getMethod())
                && !zoneText.equals(existing.getTimeZone())) {
            throw invalid("timeZone", "The saved reference timezone cannot be changed.");
        }

        SleepSession.CivilTime start = civil(request.start(), "start");
        SleepSession.CivilTime end = civil(request.end(), "end");
        Instant startAt = resolve(start, zone, "start");
        Instant endAt = resolve(end, zone, "end");
        long elapsedSeconds = ChronoUnit.SECONDS.between(startAt, endAt);
        if (elapsedSeconds < 60 || elapsedSeconds > 1440L * 60 || elapsedSeconds % 60 != 0) {
            throw invalid("end", "Elapsed duration must be 1 to 1,440 whole minutes.");
        }
        if (startAt.isAfter(now) || endAt.isAfter(now)) {
            throw invalid("end", "Sleep times cannot be in the future.");
        }
        String wakeDay = endAt.atZone(zone).toLocalDate().toString();
        if (!wakeDay.equals(end.day())) {
            throw invalid("end.day", "The end day must match the wake time in its reference timezone.");
        }
        return new SleepSession(id, owner, type, method, (int) (elapsedSeconds / 60),
                wakeDay, feeling, now, zoneText, start, end, startAt, endAt);
    }

    private static void rejectRangeFields(SessionWrite request) {
        if (request.timeZone() != null || request.start() != null || request.end() != null) {
            throw invalid("method", "Duration sessions cannot contain range fields.");
        }
    }

    private static int wholeMinutes(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("minutes", "Duration must be a whole number of minutes.");
        }
        int minutes = value.intValue();
        if (minutes < 1 || minutes > 1440) {
            throw invalid("minutes", "Duration must be 1 to 1,440 minutes.");
        }
        return minutes;
    }

    private static SleepSession.CivilTime civil(SessionWrite.CivilTime value, String field) {
        if (value == null) throw invalid(field, "A local date and time are required.");
        LocalDate day = parseDay(value.day(), field + ".day");
        if (value.hour() == null || !value.hour().matches("(?:[01][0-9]|2[0-3])")
                || value.minute() == null || !value.minute().matches("[0-5][0-9]")) {
            throw invalid(field, "Use a two-digit 24-hour clock time.");
        }
        Double offsetMinutes = null;
        JsonNode offset = value.offsetMinutes();
        if (offset != null && !offset.isNull()) {
            if (!offset.isNumber()) throw invalid(field + ".offsetMinutes", "The UTC offset must be numeric.");
            double seconds = offset.doubleValue() * 60;
            if (!Double.isFinite(seconds) || Math.abs(seconds - Math.rint(seconds)) > 1e-8) {
                throw invalid(field + ".offsetMinutes", "The UTC offset must resolve to a whole second.");
            }
            if (Math.abs(seconds) > 18 * 3600) {
                throw invalid(field + ".offsetMinutes", "The UTC offset is outside the supported range.");
            }
            offsetMinutes = Math.rint(seconds) / 60;
        }
        return new SleepSession.CivilTime(day.toString(), value.hour(), value.minute(), offsetMinutes);
    }

    private static Instant resolve(SleepSession.CivilTime civil, ZoneId zone, String field) {
        LocalDateTime local = LocalDate.parse(civil.day()).atTime(
                Integer.parseInt(civil.hour()), Integer.parseInt(civil.minute()));
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw invalid(field, "This local time does not exist because of a timezone transition.");
        if (offsets.size() > 1 && civil.offsetMinutes() == null) {
            throw invalid(field, "Choose an offset for this repeated local time.");
        }
        ZoneOffset selected = civil.offsetMinutes() == null ? offsets.getFirst()
                : ZoneOffset.ofTotalSeconds((int) Math.round(civil.offsetMinutes() * 60));
        if (!offsets.contains(selected)) {
            throw invalid(field, "The chosen offset is not valid for this local time and timezone.");
        }
        return local.toInstant(selected);
    }

    private static LocalDate parseDay(String value, String field) {
        try {
            LocalDate day = LocalDate.parse(value);
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !day.toString().equals(value)) {
                throw new DateTimeException("Not canonical.");
            }
            return day;
        } catch (DateTimeException | NullPointerException exception) {
            throw invalid(field, "Use a valid ISO calendar date.");
        }
    }

    private static String enumValue(String value, String field, String... allowed) {
        if (value == null) throw invalid(field, "A value is required.");
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw invalid(field, "Choose a supported value.");
    }

    private static String optionalEnum(String value, String field, String... allowed) {
        if (value == null) return null;
        return enumValue(value, field, allowed);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field, "A value is required.");
        return value;
    }

    private static SleepFailure invalid(String field, String message) {
        return new SleepFailure(422, VALIDATION_CODE, message, field);
    }
}
