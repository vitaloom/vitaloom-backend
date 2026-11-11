package com.vitaloom.backend.steps.service;

import com.vitaloom.backend.steps.dto.StepsBatchRequest;
import com.vitaloom.backend.steps.dto.StepsBatchResponse;

public interface StepsService {
    StepsBatchResponse ingest(String accountId, StepsBatchRequest request);
}
