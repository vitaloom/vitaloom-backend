package com.vitaloom.backend.steps.service.impl;

import com.vitaloom.backend.steps.contract.StepsReadQuery;
import com.vitaloom.backend.steps.contract.dto.StepSample;
import com.vitaloom.backend.steps.dto.request.StepsBatchRequest;
import com.vitaloom.backend.steps.dto.response.StepsBatchResponse;
import com.vitaloom.backend.steps.model.StepMeasurement;
import com.vitaloom.backend.steps.repository.StepsRepository;
import com.vitaloom.backend.steps.exception.InvalidStepsRequestException;
import com.vitaloom.backend.steps.service.StepsService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StepsServiceImpl implements StepsService, StepsReadQuery {
    private final StepsRepository repository;
    private final Clock clock;

    public StepsBatchResponse ingest(String accountId, StepsBatchRequest request) {
        if (request == null || !validText(request.deviceId(), 128) || !validText(request.runId(), 128)
                || request.events() == null || request.events().isEmpty() || request.events().size() > 500) {
            throw new InvalidStepsRequestException("INVALID_BATCH", "Batch requires deviceId, runId, and 1 to 500 events.");
        }
        Instant receivedAt = clock.instant();
        int accepted = 0, duplicates = 0, rejected = 0;
        List<StepsBatchResponse.Result> results = new ArrayList<>();
        for (int i = 0; i < request.events().size(); i++) {
            var event = request.events().get(i);
            String error = validate(event, receivedAt);
            if (error != null) {
                rejected++;
                results.add(result(i, event == null ? null : event.eventId(), "REJECTED", error));
                continue;
            }
            Instant occurredAt;
            try { occurredAt = Instant.parse(event.occurredAt()).truncatedTo(ChronoUnit.MILLIS); }
            catch (DateTimeParseException ex) {
                rejected++;
                results.add(result(i, event.eventId(), "REJECTED", "INVALID_OCCURRED_AT"));
                continue;
            }
            var measurement = new StepMeasurement(accountId, request.deviceId(), event.eventId(),
                    event.stepsDelta(), occurredAt, receivedAt);
            try {
                repository.insert(measurement);
                accepted++;
                results.add(result(i, event.eventId(), "ACCEPTED", null));
            } catch (DuplicateKeyException duplicate) {
                var existing = repository.findIdentity(accountId, request.deviceId(), event.eventId())
                        .orElseThrow(() -> duplicate);
                if (existing.getStepsDelta() == measurement.getStepsDelta()
                        && existing.getOccurredAt().equals(measurement.getOccurredAt())) {
                    duplicates++;
                    results.add(result(i, event.eventId(), "DUPLICATE", null));
                } else {
                    rejected++;
                    results.add(result(i, event.eventId(), "REJECTED", "EVENT_ID_CONFLICT"));
                }
            }
        }
        return new StepsBatchResponse(request.runId(), request.events().size(), accepted, duplicates, rejected, results);
    }

    public List<StepSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive) {
        return repository.findBetween(accountId, startInclusive, endExclusive).stream()
                .map(measurement -> new StepSample(measurement.getStepsDelta(), measurement.getOccurredAt()))
                .toList();
    }

    private static String validate(StepsBatchRequest.StepsEventRequest event, Instant receivedAt) {
        if (event == null || !validText(event.eventId(), 128)) return "INVALID_EVENT_ID";
        if (event.occurredAt() == null || event.occurredAt().isBlank()) return "INVALID_OCCURRED_AT";
        Instant occurredAt;
        try { occurredAt = Instant.parse(event.occurredAt()); }
        catch (DateTimeParseException ex) { return "INVALID_OCCURRED_AT"; }
        if (occurredAt.isAfter(receivedAt)) return "FUTURE_OCCURRED_AT";
        if (event.stepsDelta() == null || event.stepsDelta() < 1 || event.stepsDelta() > 5)
            return "INVALID_STEPS_DELTA";
        return null;
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static StepsBatchResponse.Result result(int index, String eventId, String status, String code) {
        var error = code == null ? null : new StepsBatchResponse.Error(code, message(code));
        return new StepsBatchResponse.Result(index, eventId, status, error);
    }

    private static String message(String code) {
        return switch (code) {
            case "INVALID_EVENT_ID" -> "eventId is required and must be at most 128 characters.";
            case "INVALID_OCCURRED_AT" -> "occurredAt must be an ISO-8601 instant.";
            case "FUTURE_OCCURRED_AT" -> "occurredAt must not be later than batch receipt time.";
            case "INVALID_STEPS_DELTA" -> "stepsDelta must be an integer between 1 and 5 for the MVP Producer contract.";
            case "EVENT_ID_CONFLICT" -> "eventId already exists with different measurement content.";
            default -> "Invalid measurement.";
        };
    }
}
