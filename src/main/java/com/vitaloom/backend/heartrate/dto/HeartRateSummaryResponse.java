package com.vitaloom.backend.heartrate.dto;

import java.time.Instant;
import java.time.LocalDate;

public record HeartRateSummaryResponse(LocalDate date, String timezone, Instant asOf,
                                      LatestRecorded latestRecorded, DailyAverage dailyAverage) {
    public record LatestRecorded(int heartRateBpm, Instant occurredAt) {}
    public record DailyAverage(Double heartRateBpm, String dataStatus) {}
}
