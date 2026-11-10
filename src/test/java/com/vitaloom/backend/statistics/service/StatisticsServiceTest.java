package com.vitaloom.backend.statistics.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.heartrate.service.HeartRateSample;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatisticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private final HeartRateService heartRateService = mock(HeartRateService.class);
    private final StatisticsService service =
            new StatisticsService(heartRateService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void summaryCombinesLatestFromFullHistoryWithSelectedDayAverage() {
        when(heartRateService.findLatestSample("account-a"))
                .thenReturn(Optional.of(new HeartRateSample(99, Instant.parse("2026-11-06T01:00:00Z"))));
        when(heartRateService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of(
                new HeartRateSample(70, Instant.parse("2026-11-06T00:00:00Z")),
                new HeartRateSample(81, Instant.parse("2026-11-06T00:30:00Z"))));

        HeartRateSummaryResponse summary = service.heartRateSummary("account-a", "2026-11-06");

        assertEquals(99, summary.latestRecorded().heartRateBpm());
        assertEquals(Instant.parse("2026-11-06T01:00:00Z"), summary.latestRecorded().occurredAt());
        assertEquals(75.5, summary.dailyAverage().heartRateBpm());
        assertEquals("CURRENT_DAY_PARTIAL", summary.dailyAverage().dataStatus());
        assertEquals("Asia/Ho_Chi_Minh", summary.timezone());
    }

    @Test
    void pastDayWithoutSamplesReturnsNullAverageAndKeepsLatestIndependent() {
        when(heartRateService.findLatestSample("account-a"))
                .thenReturn(Optional.of(new HeartRateSample(99, Instant.parse("2026-11-06T01:00:00Z"))));
        when(heartRateService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of());

        HeartRateSummaryResponse summary = service.heartRateSummary("account-a", "2026-11-05");

        assertEquals(99, summary.latestRecorded().heartRateBpm());
        assertNull(summary.dailyAverage().heartRateBpm());
        assertEquals("HISTORY_UNVERIFIED", summary.dailyAverage().dataStatus());
    }

    @Test
    void queriesHalfOpenHoChiMinhCalendarDayBounds() {
        when(heartRateService.findLatestSample("account-a")).thenReturn(Optional.empty());
        when(heartRateService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of());

        service.heartRateSummary("account-a", "2026-11-05");

        verify(heartRateService).findSamplesBetween(
                "account-a",
                Instant.parse("2026-11-04T17:00:00Z"),
                Instant.parse("2026-11-05T17:00:00Z"));
    }

    @Test
    void rejectsMalformedAndFutureReportDatesBeforeQueryingMetrics() {
        assertEquals("INVALID_REPORT_DATE", assertThrows(
                InvalidStatisticsRequestException.class,
                () -> service.heartRateSummary("account-a", "2026-11-6")).getCode());
        assertEquals("INVALID_REPORT_DATE", assertThrows(
                InvalidStatisticsRequestException.class,
                () -> service.heartRateSummary("account-a", "2026-11-07")).getCode());

        verifyNoInteractions(heartRateService);
    }
}
