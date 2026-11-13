package com.vitaloom.backend.sleep.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vitaloom.backend.sleep.model.SleepSession;
import java.time.Instant;

public record SessionResponse(
        String id,
        Instant confirmedAt,
        String sleepType,
        String method,
        String feeling,
        String day,
        int minutes,
        @JsonInclude(JsonInclude.Include.NON_NULL) String timeZone,
        @JsonInclude(JsonInclude.Include.NON_NULL) CivilTime start,
        @JsonInclude(JsonInclude.Include.NON_NULL) CivilTime end,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant startAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant endAt,
        boolean editable) {

    public static SessionResponse from(SleepSession session) {
        return from(session, false);
    }

    public static SessionResponse from(SleepSession session, boolean editable) {
        return new SessionResponse(session.getId(), session.getConfirmedAt(),
                session.getSleepType(), session.getMethod(), session.getFeeling(), session.getDay(),
                session.getMinutes(), session.getTimeZone(), CivilTime.from(session.getStart()), CivilTime.from(session.getEnd()),
                session.getStartAt(), session.getEndAt(), editable);
    }

    public record CivilTime(String day, String hour, String minute,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double offsetMinutes) {
        private static CivilTime from(SleepSession.CivilTime value) {
            return value == null ? null : new CivilTime(value.day(), value.hour(), value.minute(), value.offsetMinutes());
        }
    }
}
