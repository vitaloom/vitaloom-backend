package com.vitaloom.backend.statistics.dto.response;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record DailyStepsResponse(Instant startAt, Instant endAt, String timezone, int bucketSizeMinutes,
                                Long totalSteps, List<StepsTimeSeriesResponse.Bucket> buckets,
                                @JsonInclude(JsonInclude.Include.ALWAYS) Goal goal,
                                @JsonInclude(JsonInclude.Include.ALWAYS) Double progressPercent) {
    public record Goal(long targetSteps, String effectiveDate) {}
}
