package com.vitaloom.backend.statistics.service.impl;

import com.vitaloom.backend.heartrate.service.HeartRateSample;
import com.vitaloom.backend.statistics.dto.response.HeartRateTimeSeriesResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class HeartRateTimeSeriesAggregator {
    private final Instant startAt;
    private final Instant endAt;
    private final int intervalMinutes;
    private final ZoneId timeZone;
    private final List<Accumulator> accumulators;

    HeartRateTimeSeriesAggregator(Instant startAt, Instant endAt, int intervalMinutes, ZoneId timeZone) {
        this.startAt = startAt;
        this.endAt = endAt;
        this.intervalMinutes = intervalMinutes;
        this.timeZone = timeZone;
        long durationMinutes = Duration.between(startAt, endAt).toMinutes();
        if (intervalMinutes <= 0 || durationMinutes <= 0 || durationMinutes % intervalMinutes != 0) {
            throw new IllegalArgumentException("The range must contain a whole number of buckets.");
        }
        int bucketCount = Math.toIntExact(durationMinutes / intervalMinutes);
        this.accumulators = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) accumulators.add(new Accumulator());
    }

    void accept(HeartRateSample sample) {
        Instant occurredAt = sample.occurredAt();
        if (occurredAt.isBefore(startAt) || !occurredAt.isBefore(endAt)) return;

        long elapsedMinutes = Duration.between(startAt, occurredAt).toMinutes();
        int bucketIndex = (int) (elapsedMinutes / intervalMinutes);
        if (bucketIndex >= 0 && bucketIndex < accumulators.size()) {
            accumulators.get(bucketIndex).add(sample.heartRateBpm());
        }
    }

    HeartRateTimeSeriesResponse toResponse() {
        List<HeartRateTimeSeriesResponse.Bucket> buckets = new ArrayList<>(accumulators.size());
        for (int i = 0; i < accumulators.size(); i++) {
            Instant bucketStart = startAt.plus(Duration.ofMinutes((long) i * intervalMinutes));
            buckets.add(accumulators.get(i).toBucket(bucketStart));
        }
        return new HeartRateTimeSeriesResponse(startAt, endAt, timeZone.getId(),
                intervalMinutes, List.copyOf(buckets));
    }

    private static final class Accumulator {
        private Integer min;
        private Integer max;
        private long sum;
        private long count;

        void add(int bpm) {
            min = min == null ? bpm : Math.min(min, bpm);
            max = max == null ? bpm : Math.max(max, bpm);
            sum += bpm;
            count++;
        }

        HeartRateTimeSeriesResponse.Bucket toBucket(Instant startAt) {
            return new HeartRateTimeSeriesResponse.Bucket(
                    startAt, min, count == 0 ? null : (double) sum / count, max, count);
        }
    }
}
