package com.vitaloom.backend.heartrate.service;

import com.vitaloom.backend.heartrate.dto.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.HeartRateBatchResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface HeartRateService {
    HeartRateBatchResponse ingest(String accountId, HeartRateBatchRequest request);
    Optional<HeartRateSample> findLatestSample(String accountId);
    List<HeartRateSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive);
    void forEachSampleBetween(String accountId, Instant startInclusive, Instant endExclusive,
                              Consumer<HeartRateSample> consumer);
}
