package com.vitaloom.backend.heartrate.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.heartrate.dto.request.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.response.HeartRateBatchResponse;
import com.vitaloom.backend.heartrate.contract.dto.HeartRateSample;
import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.heartrate.repository.HeartRateRepository;
import com.vitaloom.backend.heartrate.service.impl.HeartRateServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HeartRateServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private final HeartRateRepository repository = mock(HeartRateRepository.class);
    private final HeartRateServiceImpl service =
            new HeartRateServiceImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void batchAcceptsValidItemsAndRejectsFutureAndInvalidBpmIndependently() {
        var request = new HeartRateBatchRequest("device", "run", List.of(
                new HeartRateBatchRequest.HeartRateEventRequest("one", "2026-11-06T02:59:00Z", 70),
                new HeartRateBatchRequest.HeartRateEventRequest("two", "2026-11-06T03:01:00Z", 71),
                new HeartRateBatchRequest.HeartRateEventRequest("three", "2026-11-06T02:59:00Z", 0)));
        when(repository.insertAll(any())).thenReturn(Set.of());
        var result = service.ingest("account-a", request);
        assertEquals(3, result.received());
        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(2, result.rejected());
        assertEquals("FUTURE_OCCURRED_AT", result.results().get(1).error().code());
        assertEquals("INVALID_HEART_RATE", result.results().get(2).error().code());
        verify(repository).insertAll(argThat(items -> items.size() == 1));
        verify(repository, never()).insert(any());
    }

    @Test
    void duplicateKeyRereadsCanonicalMeasurementAndDistinguishesRetryFromConflict() {
        var canonical = measurement("same", 72, "2026-11-06T02:00:00.123Z");
        when(repository.findIdentity("account-a", "device", "same")).thenReturn(Optional.of(canonical));
        when(repository.insertAll(any())).thenReturn(Set.of(0));

        var same = service.ingest("account-a", batch("same", "2026-11-06T02:00:00.123456789Z", 72));
        var conflict = service.ingest("account-a", batch("same", "2026-11-06T02:00:00Z", 73));

        assertEquals(1, same.duplicates());
        assertEquals(1, conflict.rejected());
        assertEquals("EVENT_ID_CONFLICT", conflict.results().get(0).error().code());
    }

    @Test
    void bulkPersistenceFailurePropagatesWithoutClaimingPerEventSuccess() {
        var request = new HeartRateBatchRequest("device", "run", List.of(
                new HeartRateBatchRequest.HeartRateEventRequest("one", "2026-11-06T02:59:00Z", 70),
                new HeartRateBatchRequest.HeartRateEventRequest("two", "2026-11-06T02:59:01Z", 71)));
        doThrow(new IllegalStateException("database unavailable")).when(repository).insertAll(any());

        assertThrows(IllegalStateException.class, () -> service.ingest("account-a", request));
        verify(repository).insertAll(argThat(items -> items.size() == 2));
    }

    @Test
    void batchDeduplicatesIdentityBeforeUnorderedInsertAndKeepsFirstValidPayload() {
        when(repository.insertAll(any())).thenReturn(Set.of());
        var request = new HeartRateBatchRequest("device", "run", List.of(
                new HeartRateBatchRequest.HeartRateEventRequest("same", "2026-11-06T02:00:00.123456789Z", 72),
                new HeartRateBatchRequest.HeartRateEventRequest("same", "2026-11-06T02:00:00.123Z", 72),
                new HeartRateBatchRequest.HeartRateEventRequest("same", "2026-11-06T02:00:01Z", 73),
                new HeartRateBatchRequest.HeartRateEventRequest("other", "2026-11-06T02:00:02Z", 74)));

        var response = service.ingest("account-a", request);

        assertEquals(2, response.accepted());
        assertEquals(1, response.duplicates());
        assertEquals(1, response.rejected());
        assertEquals(List.of(0, 1, 2, 3), response.results().stream()
                .map(HeartRateBatchResponse.Result::index).toList());
        assertEquals("ACCEPTED", response.results().get(0).status());
        assertEquals("DUPLICATE", response.results().get(1).status());
        assertEquals("EVENT_ID_CONFLICT", response.results().get(2).error().code());
        verify(repository).insertAll(argThat(items -> items.size() == 2
                && items.get(0).getHeartRateBpm() == 72 && items.get(1).getHeartRateBpm() == 74));
    }

    @Test
    void exposesLatestRawSampleThroughMetricQueryCapability() {
        var expected = measurement("latest", 99, "2026-11-05T23:30:00Z");
        when(repository.latest("account-a")).thenReturn(Optional.of(expected));

        assertEquals(Optional.of(new HeartRateSample(99, expected.getOccurredAt())),
                service.findLatestSample("account-a"));
    }

    @Test
    void exposesRawSamplesForRequestedTimeRangeThroughMetricQueryCapability() {
        var first = measurement("first", 70, "2026-11-05T17:00:00Z");
        var second = measurement("second", 81, "2026-11-05T17:30:00Z");
        when(repository.findBetween("account-a", first.getOccurredAt(), second.getOccurredAt()))
                .thenReturn(List.of(first, second));

        assertEquals(List.of(
                        new HeartRateSample(70, first.getOccurredAt()),
                        new HeartRateSample(81, second.getOccurredAt())),
                service.findSamplesBetween("account-a", first.getOccurredAt(), second.getOccurredAt()));
    }

    @Test
    void streamsRawSamplesAndClosesRepositoryCursorWhenConsumptionCompletes() {
        var sample = measurement("streamed", 77, "2026-11-05T17:00:00Z");
        var cursorClosed = new AtomicBoolean();
        when(repository.streamBetween("account-a", sample.getOccurredAt(), sample.getOccurredAt().plusSeconds(1)))
                .thenReturn(Stream.of(sample).onClose(() -> cursorClosed.set(true)));
        var received = new java.util.ArrayList<HeartRateSample>();

        service.forEachSampleBetween("account-a", sample.getOccurredAt(), sample.getOccurredAt().plusSeconds(1),
                received::add);

        assertEquals(List.of(new HeartRateSample(77, sample.getOccurredAt())), received);
        assertTrue(cursorClosed.get());
    }

    private static HeartRateBatchRequest batch(String id, String timestamp, int bpm) {
        return new HeartRateBatchRequest("device", "run", List.of(
                new HeartRateBatchRequest.HeartRateEventRequest(id, timestamp, bpm)));
    }

    private static HeartRateMeasurement measurement(String id, int bpm, String time) {
        return new HeartRateMeasurement("account-a", "device", id, bpm, Instant.parse(time), NOW);
    }
}
