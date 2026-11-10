package com.vitaloom.backend.steps.dto;

import java.util.List;

public record StepsBatchRequest(String deviceId, String runId, List<StepsEventRequest> events) {
    public record StepsEventRequest(String eventId, String occurredAt, Integer stepsDelta) {}
}
