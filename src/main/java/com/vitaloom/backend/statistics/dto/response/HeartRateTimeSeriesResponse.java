package com.vitaloom.backend.statistics.dto.response;

import java.time.Instant;
import java.util.List;

public record HeartRateTimeSeriesResponse(Instant startAt, Instant endAt, String timezone,
                                          int intervalMinutes, List<Bucket> buckets) {
    public record Bucket(Instant startAt, Integer minBpm, Double avgBpm, Integer maxBpm, long sampleCount) {}
}
