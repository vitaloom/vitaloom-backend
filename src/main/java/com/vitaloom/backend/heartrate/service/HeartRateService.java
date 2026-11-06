package com.vitaloom.backend.heartrate.service;

import com.vitaloom.backend.heartrate.dto.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.HeartRateBatchResponse;
import com.vitaloom.backend.heartrate.dto.HeartRateSummaryResponse;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.heartrate.repository.HeartRateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HeartRateService {
    public static final ZoneId TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final HeartRateRepository repository;
    private final Clock clock;

    @Autowired
    public HeartRateService(HeartRateRepository repository) { this(repository, Clock.systemUTC()); }
    HeartRateService(HeartRateRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    public HeartRateBatchResponse ingest(String accountId, HeartRateBatchRequest request) {
        if (request == null || !validText(request.deviceId(), 128) || !validText(request.runId(), 128)
                || request.events() == null || request.events().isEmpty() || request.events().size() > 500) {
            throw new InvalidHeartRateRequestException("INVALID_BATCH", "Batch requires deviceId, runId, and 1 to 500 events.");
        }
        Instant receivedAt = clock.instant();
        int accepted = 0, duplicates = 0, rejected = 0;
        List<HeartRateBatchResponse.Result> results = new ArrayList<>();
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
            var measurement = new HeartRateMeasurement(accountId, request.deviceId(), event.eventId(),
                    event.heartRateBpm(), occurredAt, receivedAt);
            try {
                repository.insert(measurement);
                accepted++;
                results.add(result(i, event.eventId(), "ACCEPTED", null));
            } catch (DuplicateKeyException duplicate) {
                var existing = repository.findIdentity(accountId, request.deviceId(), event.eventId())
                        .orElseThrow(() -> duplicate);
                if (existing.getHeartRateBpm() == measurement.getHeartRateBpm()
                        && existing.getOccurredAt().equals(measurement.getOccurredAt())) {
                    duplicates++;
                    results.add(result(i, event.eventId(), "DUPLICATE", null));
                } else {
                    rejected++;
                    results.add(result(i, event.eventId(), "REJECTED", "EVENT_ID_CONFLICT"));
                }
            }
        }
        return new HeartRateBatchResponse(request.runId(), request.events().size(), accepted, duplicates, rejected, results);
    }

    public HeartRateSummaryResponse summary(String accountId, String date) {
        Instant asOf = clock.instant();
        LocalDate selected;
        try { selected = date == null ? LocalDate.now(clock.withZone(TIMEZONE)) : LocalDate.parse(date); }
        catch (DateTimeParseException ex) { throw invalidDate(); }
        LocalDate today = LocalDate.now(clock.withZone(TIMEZONE));
        if (selected.isAfter(today) || (date != null && !selected.toString().equals(date))) throw invalidDate();
        var latest = repository.latest(accountId).map(m -> new HeartRateSummaryResponse.LatestRecorded(
                m.getHeartRateBpm(), m.getOccurredAt())).orElse(null);
        Instant start = selected.atStartOfDay(TIMEZONE).toInstant();
        Instant end = selected.plusDays(1).atStartOfDay(TIMEZONE).toInstant();
        List<HeartRateMeasurement> samples = repository.forDay(accountId, start, end);
        Double average = samples.isEmpty() ? null : samples.stream().mapToLong(HeartRateMeasurement::getHeartRateBpm)
                .average().orElseThrow();
        String status = selected.equals(today) ? "CURRENT_DAY_PARTIAL" : "HISTORY_UNVERIFIED";
        return new HeartRateSummaryResponse(selected, TIMEZONE.getId(), asOf, latest,
                new HeartRateSummaryResponse.DailyAverage(average, status));
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
    private static InvalidHeartRateRequestException invalidDate() {
        return new InvalidHeartRateRequestException("INVALID_REPORT_DATE", "date must be a valid YYYY-MM-DD date that is not in the future.");
    }
}
