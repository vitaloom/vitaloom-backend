package com.vitaloom.backend.heartrate.contract;

import com.vitaloom.backend.heartrate.contract.dto.HeartRateSample;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface HeartRateReadQuery {
    Optional<HeartRateSample> findLatestSample(String accountId);

    List<HeartRateSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive);

    void forEachSampleBetween(String accountId, Instant startInclusive, Instant endExclusive,
                              Consumer<HeartRateSample> consumer);
}
