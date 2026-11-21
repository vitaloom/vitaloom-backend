package com.vitaloom.backend.heartrate.service.impl;

import com.vitaloom.backend.heartrate.contract.HeartRateReadQuery;
import com.vitaloom.backend.heartrate.contract.dto.HeartRateSample;
import com.vitaloom.backend.heartrate.dto.request.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.response.HeartRateBatchResponse;
import com.vitaloom.backend.heartrate.mapper.HeartRateMapper;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.heartrate.repository.HeartRateRepository;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import com.vitaloom.backend.heartrate.exception.InvalidHeartRateRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HeartRateServiceImpl implements HeartRateService, HeartRateReadQuery {
    private final HeartRateRepository repository;
    private final Clock clock;

    public HeartRateBatchResponse ingest(String accountId, HeartRateBatchRequest request) {
        if (request == null || !validText(request.deviceId(), 128) || !validText(request.runId(), 128)
                || request.events() == null || request.events().isEmpty() || request.events().size() > 500) {
            throw new InvalidHeartRateRequestException("INVALID_BATCH", "Batch requires deviceId, runId, and 1 to 500 events.");
        }
        Instant receivedAt = clock.instant();
        List<HeartRateBatchResponse.Result> results = new ArrayList<>(
                java.util.Collections.nCopies(request.events().size(), null));
        Map<String, List<Pending>> byEventId = new LinkedHashMap<>();
        for (int i = 0; i < request.events().size(); i++) {
            var event = request.events().get(i);
            String error = validate(event, receivedAt);
            if (error != null) {
                results.set(i, result(i, event == null ? null : event.eventId(), "REJECTED", error));
                continue;
            }
            Instant occurredAt;
            try { occurredAt = Instant.parse(event.occurredAt()).truncatedTo(ChronoUnit.MILLIS); }
            catch (DateTimeParseException ex) {
                results.set(i, result(i, event.eventId(), "REJECTED", "INVALID_OCCURRED_AT"));
                continue;
            }
            var measurement = new HeartRateMeasurement(accountId, request.deviceId(), event.eventId(),
                    event.heartRateBpm(), occurredAt, receivedAt);
            byEventId.computeIfAbsent(event.eventId(), ignored -> new ArrayList<>())
                    .add(new Pending(i, event.eventId(), measurement));
        }

        List<Pending> canonical = byEventId.values().stream().map(group -> group.get(0)).toList();
        Set<Integer> duplicateWriteIndexes = canonical.isEmpty() ? Set.of()
                : repository.insertAll(canonical.stream().map(Pending::measurement).toList());
        for (int bulkIndex = 0; bulkIndex < canonical.size(); bulkIndex++) {
            List<Pending> group = byEventId.get(canonical.get(bulkIndex).eventId());
            HeartRateMeasurement existing = duplicateWriteIndexes.contains(bulkIndex)
                    ? repository.findIdentity(accountId, request.deviceId(), canonical.get(bulkIndex).eventId())
                            .orElseThrow(() -> new IllegalStateException("Duplicate Heart Rate identity was not found after bulk insert."))
                    : canonical.get(bulkIndex).measurement();
            for (int memberIndex = 0; memberIndex < group.size(); memberIndex++) {
                Pending pending = group.get(memberIndex);
                boolean same = samePayload(existing, pending.measurement());
                String status = duplicateWriteIndexes.contains(bulkIndex) || memberIndex > 0
                        ? (same ? "DUPLICATE" : "REJECTED")
                        : "ACCEPTED";
                String error = "REJECTED".equals(status) ? "EVENT_ID_CONFLICT" : null;
                results.set(pending.index(), result(pending.index(), pending.eventId(), status, error));
            }
        }
        int accepted = (int) results.stream().filter(item -> "ACCEPTED".equals(item.status())).count();
        int duplicates = (int) results.stream().filter(item -> "DUPLICATE".equals(item.status())).count();
        int rejected = results.size() - accepted - duplicates;
        return new HeartRateBatchResponse(request.runId(), request.events().size(), accepted, duplicates, rejected, results);
    }

    private static boolean samePayload(HeartRateMeasurement left, HeartRateMeasurement right) {
        return left.getHeartRateBpm() == right.getHeartRateBpm()
                && left.getOccurredAt().equals(right.getOccurredAt());
    }

    private record Pending(int index, String eventId, HeartRateMeasurement measurement) { }

    public Optional<HeartRateSample> findLatestSample(String accountId) {
        return repository.latest(accountId)
                .map(HeartRateMapper::toSample);
    }

    public List<HeartRateSample> findSamplesBetween(String accountId, Instant startInclusive, Instant endExclusive) {
        return repository.findBetween(accountId, startInclusive, endExclusive).stream()
                .map(HeartRateMapper::toSample)
                .toList();
    }

    public void forEachSampleBetween(String accountId, Instant startInclusive, Instant endExclusive,
                                     Consumer<HeartRateSample> consumer) {
        try (Stream<HeartRateMeasurement> measurements = repository.streamBetween(
                accountId, startInclusive, endExclusive)) {
            measurements.map(HeartRateMapper::toSample)
                    .forEach(consumer);
        }
    }

    private static String validate(HeartRateBatchRequest.HeartRateEventRequest event, Instant receivedAt) {
        if (event == null || !validText(event.eventId(), 128)) return "INVALID_EVENT_ID";
        if (event.occurredAt() == null || event.occurredAt().isBlank()) return "INVALID_OCCURRED_AT";
        Instant occurredAt;
        try { occurredAt = Instant.parse(event.occurredAt()); }
        catch (DateTimeParseException ex) { return "INVALID_OCCURRED_AT"; }
        if (occurredAt.isAfter(receivedAt)) return "FUTURE_OCCURRED_AT";
        if (event.heartRateBpm() == null || event.heartRateBpm() < 1 || event.heartRateBpm() > 300)
            return "INVALID_HEART_RATE";
        return null;
    }
    private static boolean validText(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static HeartRateBatchResponse.Result result(int index, String eventId, String status, String code) {
        var error = code == null ? null : new HeartRateBatchResponse.Error(code, message(code));
        return new HeartRateBatchResponse.Result(index, eventId, status, error);
    }
    private static String message(String code) {
        return switch (code) {
            case "INVALID_EVENT_ID" -> "eventId is required and must be at most 128 characters.";
            case "INVALID_OCCURRED_AT" -> "occurredAt must be an ISO-8601 instant.";
            case "FUTURE_OCCURRED_AT" -> "occurredAt must not be later than batch receipt time.";
            case "INVALID_HEART_RATE" -> "heartRateBpm must be an integer between 1 and 300.";
            case "EVENT_ID_CONFLICT" -> "eventId already exists with different measurement content.";
            default -> "Invalid measurement.";
        };
    }
}
