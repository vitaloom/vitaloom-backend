package com.vitaloom.backend.statistics.dto.response;

import java.time.Instant;
import java.util.List;

public record StepsTimeSeriesResponse(Instant startAt, Instant endAt, String timezone, int bucketSizeMinutes,
                                     Long totalSteps, List<Bucket> buckets) {
    public record Bucket(Instant startAt, Instant endAt, Long steps, long eventCount) {}
}
