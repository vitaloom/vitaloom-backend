package com.vitaloom.backend.steps.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record StepsBatchResponse(String runId, int received, int accepted, int duplicates, int rejected,
                                 List<Result> results) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(int index, String eventId, String status, Error error) {}
    public record Error(String code, String message) {}
}
