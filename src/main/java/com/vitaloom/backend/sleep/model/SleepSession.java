package com.vitaloom.backend.sleep.model;

import com.vitaloom.backend.shared.persistence.BaseDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("sleep_sessions")
@Getter
@NoArgsConstructor
public class SleepSession extends BaseDocument {
    @Field("owner") private String owner;
    @Field("sleep_type") private String sleepType;
    @Field("method") private String method;
    @Field("minutes") private int minutes;
    @Field("statistical_day") private String day;
    @Field("feeling") private String feeling;
    @Field("confirmed_at") private Instant confirmedAt;
    @Field("time_zone") private String timeZone;
    @Field("start") private CivilTime start;
    @Field("end") private CivilTime end;
    @Field("start_at") private Instant startAt;
    @Field("end_at") private Instant endAt;

    public SleepSession(String id, String owner, String sleepType, String method, int minutes,
                        String day, String feeling, Instant confirmedAt,
                        String timeZone, CivilTime start, CivilTime end, Instant startAt, Instant endAt) {
        setId(id);
        this.owner = owner;
        this.sleepType = sleepType;
        this.method = method;
        this.minutes = minutes;
        this.day = day;
        this.feeling = feeling;
        this.confirmedAt = confirmedAt;
        this.timeZone = timeZone;
        this.start = start;
        this.end = end;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public SleepSession withConfirmedAt(Instant value) {
        return new SleepSession(getId(), owner, sleepType, method, minutes, day, feeling, value,
                timeZone, start, end, startAt, endAt);
    }

    public record CivilTime(
            @org.springframework.data.mongodb.core.mapping.Field("day") String day,
            @org.springframework.data.mongodb.core.mapping.Field("hour") String hour,
            @org.springframework.data.mongodb.core.mapping.Field("minute") String minute,
            @org.springframework.data.mongodb.core.mapping.Field("offset_minutes") Double offsetMinutes) {}
}
