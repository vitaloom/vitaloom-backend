package com.vitaloom.backend.steps.dto.request;

import java.util.List;

public record StepsBatchRequest(String deviceId, String runId, List<StepsEventRequest> events) {
    public record StepsEventRequest(String eventId, String occurredAt, String startAt, String endAt, Number stepsDelta) {
        public StepsEventRequest(String eventId, String occurredAt, Integer stepsDelta) {
            this(eventId, occurredAt, null, null, stepsDelta);
        }
    }
}
