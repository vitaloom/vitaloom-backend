package com.vitaloom.backend.steps.service;

import com.vitaloom.backend.steps.dto.request.StepsBatchRequest;
import com.vitaloom.backend.steps.dto.response.StepsBatchResponse;

public interface StepsService {
    StepsBatchResponse ingest(String accountId, StepsBatchRequest request);
}
