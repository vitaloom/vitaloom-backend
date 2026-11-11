package com.vitaloom.backend.steps.service;

import com.vitaloom.backend.steps.dto.StepsBatchRequest;
import com.vitaloom.backend.steps.dto.StepsBatchResponse;
import java.time.Instant;
import java.util.List;

public interface StepsService {
    StepsBatchResponse ingest(String accountId, StepsBatchRequest request);
    List<StepSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive);
}
