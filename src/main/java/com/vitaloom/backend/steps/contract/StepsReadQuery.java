package com.vitaloom.backend.steps.contract;

import com.vitaloom.backend.steps.contract.dto.StepSample;
import java.time.Instant;
import java.util.List;

public interface StepsReadQuery {
    List<StepSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive);
}
