package com.vitaloom.backend.heartrate.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.heartrate.dto.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.heartrate.repository.HeartRateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class HeartRateServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private final HeartRateRepository repository = mock(HeartRateRepository.class);
    private final HeartRateService service = new HeartRateService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void batchAcceptsValidItemsAndRejectsFutureAndInvalidBpmIndependently() {
        var request = new HeartRateBatchRequest("device", "run", java.util.List.of(
                new HeartRateBatchRequest.HeartRateEventRequest("one", "2026-11-06T02:59:00Z", 70),
                new HeartRateBatchRequest.HeartRateEventRequest("two", "2026-11-06T03:01:00Z", 71),
                new HeartRateBatchRequest.HeartRateEventRequest("three", "2026-11-06T02:59:00Z", 0)));
        var result = service.ingest("account-a", request);
        assertEquals(3, result.received());
        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(2, result.rejected());
        assertEquals("FUTURE_OCCURRED_AT", result.results().get(1).error().code());
        assertEquals("INVALID_HEART_RATE", result.results().get(2).error().code());
        verify(repository, times(1)).insert(any());
    }

    @Test
    void duplicateKeyRereadsCanonicalMeasurementAndDistinguishesRetryFromConflict() {
        var canonical = new HeartRateMeasurement("account-a", "device", "same", 72,
                Instant.parse("2026-11-06T02:00:00.123Z"), NOW.minusSeconds(10));
        when(repository.findIdentity("account-a", "device", "same")).thenReturn(Optional.of(canonical));
        doThrow(new DuplicateKeyException("duplicate")).when(repository).insert(any());
        var same = service.ingest("account-a", batch("same", "2026-11-06T02:00:00.123456789Z", 72));
        var conflict = service.ingest("account-a", batch("same", "2026-11-06T02:00:00Z", 73));
        assertEquals(1, same.duplicates());
        assertEquals(1, conflict.rejected());
        assertEquals("EVENT_ID_CONFLICT", conflict.results().get(0).error().code());
    }

    @Test
    void persistenceFailurePropagatesAfterAnyPriorAcceptedItemForSafeRetry() {
        var request = new HeartRateBatchRequest("device", "run", java.util.List.of(
                new HeartRateBatchRequest.HeartRateEventRequest("one", "2026-11-06T02:59:00Z", 70),
                new HeartRateBatchRequest.HeartRateEventRequest("two", "2026-11-06T02:59:01Z", 71)));
        doNothing().doThrow(new IllegalStateException("database unavailable")).when(repository).insert(any());
        assertThrows(IllegalStateException.class, () -> service.ingest("account-a", request));
        verify(repository, times(2)).insert(any());
    }

    @Test
    void summaryUsesIndependentLatestAndLocalDayMeanAndNeverVerifiesHistory() {
        var latest = new HeartRateMeasurement("account-a", "device", "late", 99,
                Instant.parse("2026-11-05T23:30:00Z"), NOW);
        when(repository.latest("account-a")).thenReturn(Optional.of(latest));
        when(repository.forDay(eq("account-a"), any(), any())).thenReturn(java.util.List.of(
                measurement(70, "2026-11-05T17:00:00Z"), measurement(81, "2026-11-05T17:30:00Z")));
        var summary = service.summary("account-a", "2026-11-06");
        assertEquals(99, summary.latestRecorded().heartRateBpm());
        assertEquals(75.5, summary.dailyAverage().heartRateBpm());
        assertEquals("CURRENT_DAY_PARTIAL", summary.dailyAverage().dataStatus());
        when(repository.forDay(eq("account-a"), any(), any())).thenReturn(java.util.List.of());
        var past = service.summary("account-a", "2026-11-05");
        assertNull(past.dailyAverage().heartRateBpm());
        assertEquals("HISTORY_UNVERIFIED", past.dailyAverage().dataStatus());
        assertEquals(99, past.latestRecorded().heartRateBpm());
    }

    @Test
    void summaryUsesHalfOpenHoChiMinhCalendarDayBounds() {
        when(repository.latest("account-a")).thenReturn(Optional.empty());
        service.summary("account-a", "2026-11-05");
        verify(repository).forDay("account-a", Instant.parse("2026-11-04T17:00:00Z"),
                Instant.parse("2026-11-05T17:00:00Z"));
    }

    @Test
    void rejectsMalformedAndFutureReportDates() {
        assertEquals("INVALID_REPORT_DATE", assertThrows(InvalidHeartRateRequestException.class,
                () -> service.summary("account-a", "2026-11-6")).getCode());
        assertEquals("INVALID_REPORT_DATE", assertThrows(InvalidHeartRateRequestException.class,
                () -> service.summary("account-a", "2026-11-07")).getCode());
        verifyNoInteractions(repository);
    }

    private static HeartRateBatchRequest batch(String id, String timestamp, int bpm) {
        return new HeartRateBatchRequest("device", "run", java.util.List.of(
                new HeartRateBatchRequest.HeartRateEventRequest(id, timestamp, bpm)));
    }
    private static HeartRateMeasurement measurement(int bpm, String time) {
        return new HeartRateMeasurement("account-a", "device", "event-" + bpm, bpm, Instant.parse(time), NOW);
    }
}
