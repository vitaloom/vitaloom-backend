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
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StepsServiceImpl implements StepsService, StepsReadQuery {
    private static final ZoneId STEP_BUCKET_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final StepsRepository repository;
    private final Clock clock;

    public StepsBatchResponse ingest(String accountId, StepsBatchRequest request) {
        if (request == null || !validText(request.deviceId(), 128) || !validText(request.runId(), 128)
                || request.events() == null || request.events().isEmpty() || request.events().size() > 500) {
            throw new InvalidStepsRequestException("INVALID_BATCH", "Batch requires deviceId, runId, and 1 to 500 events.");
        }
        Instant receivedAt = clock.instant();
        List<StepsBatchResponse.Result> results = new ArrayList<>(
                java.util.Collections.nCopies(request.events().size(), null));
        Map<String, List<Pending>> byEventId = new LinkedHashMap<>();
        for (int i = 0; i < request.events().size(); i++) {
            var event = request.events().get(i);
            String error = validate(event, receivedAt);
            if (error != null) {
                results.set(i, result(i, event == null ? null : event.eventId(), "REJECTED", error));
                continue;
            }
            Instant occurredAt = event.occurredAt() == null
                    ? Instant.parse(event.startAt()).truncatedTo(ChronoUnit.MILLIS)
                    : Instant.parse(event.occurredAt()).truncatedTo(ChronoUnit.MILLIS);
            Instant endAt = event.endAt() == null ? null
                    : Instant.parse(event.endAt()).truncatedTo(ChronoUnit.MILLIS);
            var measurement = new StepMeasurement(accountId, request.deviceId(), event.eventId(),
                    event.stepsDelta().intValue(), occurredAt, endAt, receivedAt);
            byEventId.computeIfAbsent(event.eventId(), ignored -> new ArrayList<>())
                    .add(new Pending(i, event.eventId(), measurement));
        }
        List<Pending> canonical = byEventId.values().stream().map(group -> group.get(0)).toList();
        Set<Integer> duplicateWriteIndexes = canonical.isEmpty() ? Set.of()
                : repository.insertAll(canonical.stream().map(Pending::measurement).toList());
        for (int bulkIndex = 0; bulkIndex < canonical.size(); bulkIndex++) {
            List<Pending> group = byEventId.get(canonical.get(bulkIndex).eventId());
            StepMeasurement existing = duplicateWriteIndexes.contains(bulkIndex)
                    ? repository.findIdentity(accountId, request.deviceId(), canonical.get(bulkIndex).eventId())
                            .orElseThrow(() -> new IllegalStateException("Duplicate Steps identity was not found after bulk insert."))
                    : canonical.get(bulkIndex).measurement();
            for (int memberIndex = 0; memberIndex < group.size(); memberIndex++) {
                Pending pending = group.get(memberIndex);
                boolean same = samePayload(existing, pending.measurement());
                String status = duplicateWriteIndexes.contains(bulkIndex) || memberIndex > 0
                        ? (same ? "DUPLICATE" : "REJECTED")
                        : "ACCEPTED";
                String itemError = "REJECTED".equals(status) ? "EVENT_ID_CONFLICT" : null;
                results.set(pending.index(), result(pending.index(), pending.eventId(), status, itemError));
            }
        }
        int accepted = (int) results.stream().filter(item -> "ACCEPTED".equals(item.status())).count();
        int duplicates = (int) results.stream().filter(item -> "DUPLICATE".equals(item.status())).count();
        int rejected = results.size() - accepted - duplicates;
        return new StepsBatchResponse(request.runId(), request.events().size(), accepted, duplicates, rejected, results);
    }

    private static boolean samePayload(StepMeasurement left, StepMeasurement right) {
        return left.getStepsDelta() == right.getStepsDelta()
                && left.getOccurredAt().equals(right.getOccurredAt())
                && java.util.Objects.equals(left.getEndAt(), right.getEndAt());
    }

    private record Pending(int index, String eventId, StepMeasurement measurement) { }

    public List<StepSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive) {
        return repository.findBetween(accountId, startInclusive, endExclusive).stream()
                .map(measurement -> new StepSample(measurement.getStepsDelta(), measurement.getOccurredAt()))
                .toList();
    }

    private static String validate(StepsBatchRequest.StepsEventRequest event, Instant receivedAt) {
        if (event == null || !validText(event.eventId(), 128)) return "INVALID_EVENT_ID";
        boolean legacy = event.occurredAt() != null;
        boolean hasStart = event.startAt() != null;
        boolean hasEnd = event.endAt() != null;
        if (legacy && (hasStart || hasEnd)) return "INVALID_TIME_FORM";
        if (legacy) {
            if (event.occurredAt().isBlank()) return "INVALID_OCCURRED_AT";
            Instant occurredAt;
            try { occurredAt = Instant.parse(event.occurredAt()); }
            catch (DateTimeParseException ex) { return "INVALID_OCCURRED_AT"; }
            if (occurredAt.isAfter(receivedAt)) return "FUTURE_OCCURRED_AT";
            Integer stepsDelta = integerValue(event.stepsDelta());
            if (stepsDelta == null || stepsDelta < 1 || stepsDelta > 5) return "INVALID_STEPS_DELTA";
            return null;
        }
        if (!hasStart || !hasEnd || event.startAt().isBlank() || event.endAt().isBlank()) {
            return "INVALID_INTERVAL";
        }
        Instant startAt;
        Instant endAt;
        try { startAt = Instant.parse(event.startAt()); }
        catch (DateTimeParseException ex) { return "INVALID_START_AT"; }
        try { endAt = Instant.parse(event.endAt()); }
        catch (DateTimeParseException ex) { return "INVALID_END_AT"; }
        if (endAt.isAfter(receivedAt)) return "FUTURE_END_AT";
        Instant normalizedStart = startAt.truncatedTo(ChronoUnit.MILLIS);
        Instant normalizedEnd = endAt.truncatedTo(ChronoUnit.MILLIS);
        Duration duration = Duration.between(normalizedStart, normalizedEnd);
        if (duration.isNegative() || duration.isZero() || duration.getNano() != 0
                || duration.getSeconds() > 3600) return "INVALID_INTERVAL";
        ZonedDateTime bucketStart = normalizedStart.atZone(STEP_BUCKET_ZONE)
                .withMinute(0).withSecond(0).withNano(0);
        if (normalizedEnd.isAfter(bucketStart.plusHours(1).toInstant())) return "INVALID_INTERVAL";
        long maxSteps = 5L * duration.getSeconds();
        Integer stepsDelta = integerValue(event.stepsDelta());
        if (stepsDelta == null || stepsDelta < 1 || stepsDelta > maxSteps) return "INVALID_STEPS_DELTA";
        return null;
    }

    private static Integer integerValue(Number value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger)) return null;
        try { return new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (ArithmeticException exception) { return null; }
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
            case "INVALID_START_AT" -> "startAt must be an ISO-8601 instant.";
            case "INVALID_END_AT" -> "endAt must be an ISO-8601 instant.";
            case "INVALID_TIME_FORM" -> "Provide occurredAt or the startAt/endAt interval, not both.";
            case "INVALID_INTERVAL" -> "startAt/endAt must describe a whole-second interval of 1 to 3600 seconds within one local hour.";
            case "FUTURE_END_AT" -> "endAt must not be later than batch receipt time.";
            case "INVALID_STEPS_DELTA" -> "stepsDelta must be a positive integer within the allowed interval limit.";
            case "EVENT_ID_CONFLICT" -> "eventId already exists with different measurement content.";
            default -> "Invalid measurement.";
        };
    }
}
