package com.vitaloom.backend.heartrate.dto.request;

import java.util.List;

public record HeartRateBatchRequest(String deviceId, String runId, List<HeartRateEventRequest> events) {
    public record HeartRateEventRequest(String eventId, String occurredAt, Integer heartRateBpm) {}
}
