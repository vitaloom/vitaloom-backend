package com.vitaloom.backend.heartrate.dto;

import java.util.List;

public record HeartRateBatchResponse(String runId, int received, int accepted, int duplicates, int rejected,
                                     List<Result> results) {
    public record Result(int index, String eventId, String status, Error error) {}
    public record Error(String code, String message) {}
}
