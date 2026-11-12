package com.vitaloom.backend.statistics.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.heartrate.contract.HeartRateReadQuery;
import com.vitaloom.backend.heartrate.contract.dto.HeartRateSample;
import com.vitaloom.backend.steps.contract.StepsReadQuery;
import com.vitaloom.backend.steps.goal.contract.StepGoalReadQuery;
import com.vitaloom.backend.steps.goal.contract.dto.EffectiveStepGoal;
import com.vitaloom.backend.statistics.dto.response.DailyStepsResponse;
import com.vitaloom.backend.statistics.service.impl.StatisticsServiceImpl;
import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.dto.response.HeartRateTimeSeriesResponse;
import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StatisticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private final HeartRateReadQuery heartRateService = mock(HeartRateReadQuery.class);
    private final StepsReadQuery stepsService = mock(StepsReadQuery.class);
    private final StepGoalReadQuery stepGoalReadQuery = mock(StepGoalReadQuery.class);
    private final StatisticsService service =
            new StatisticsServiceImpl(heartRateService, stepsService, stepGoalReadQuery, Clock.fixed(NOW, ZoneOffset.UTC),
                    ZoneId.of("Asia/Ho_Chi_Minh"));

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

    @Test
    void dailyTimeSeriesUsesLocalDefaultAndAggregatesUnorderedHalfOpenSamples() {
        doAnswer(invocation -> {
            Consumer<HeartRateSample> consumer = invocation.getArgument(3);
            consumer.accept(new HeartRateSample(72, Instant.parse("2026-11-05T17:15:00Z")));
            consumer.accept(new HeartRateSample(84, Instant.parse("2026-11-05T17:14:59.999Z")));
            consumer.accept(new HeartRateSample(65, Instant.parse("2026-11-05T17:00:00Z")));
            consumer.accept(new HeartRateSample(99, Instant.parse("2026-11-06T17:00:00Z")));
            return null;
        }).when(heartRateService).forEachSampleBetween(eq("account-a"), any(), any(), any());

        HeartRateTimeSeriesResponse result = service.dailyHeartRateTimeSeries("account-a", null);

        assertEquals(96, result.buckets().size());
        assertEquals(15, result.intervalMinutes());
        assertEquals(Instant.parse("2026-11-05T17:00:00Z"), result.startAt());
        assertEquals(Instant.parse("2026-11-06T17:00:00Z"), result.endAt());
        assertEquals(new HeartRateTimeSeriesResponse.Bucket(
                        Instant.parse("2026-11-05T17:00:00Z"), 65, 74.5, 84, 2), result.buckets().get(0));
        assertEquals(new HeartRateTimeSeriesResponse.Bucket(
                        Instant.parse("2026-11-05T17:15:00Z"), 72, 72.0, 72, 1), result.buckets().get(1));
        assertNull(result.buckets().get(2).avgBpm());
        assertEquals(0, result.buckets().get(2).sampleCount());
        verify(heartRateService).forEachSampleBetween(
                eq("account-a"), eq(result.startAt()), eq(result.endAt()), any());
    }

    @Test
    void weeklyTimeSeriesUsesCurrentMondayAndKeepsFuturePositionsEmpty() {
        doAnswer(invocation -> {
            Consumer<HeartRateSample> consumer = invocation.getArgument(3);
            consumer.accept(new HeartRateSample(75, Instant.parse("2026-11-06T02:00:00Z")));
            consumer.accept(new HeartRateSample(88, Instant.parse("2026-11-06T03:00:01Z")));
            return null;
        }).when(heartRateService).forEachSampleBetween(eq("account-a"), any(), any(), any());

        HeartRateTimeSeriesResponse result = service.weeklyHeartRateTimeSeries("account-a", null);

        assertEquals(168, result.buckets().size());
        assertEquals(60, result.intervalMinutes());
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), result.startAt());
        assertEquals(Instant.parse("2026-11-08T17:00:00Z"), result.endAt());
        assertEquals(1, result.buckets().get(105).sampleCount());
        assertEquals(75.0, result.buckets().get(105).avgBpm());
        assertTrue(result.buckets().stream().skip(106).allMatch(bucket -> bucket.sampleCount() == 0));
        verify(heartRateService).forEachSampleBetween(
                eq("account-a"), eq(result.startAt()), eq(result.endAt()), any());
    }

    @Test
    void customTimeSeriesSupportsInclusiveOneAndThirtyDayRangesAndRejectsInvalidRanges() {
        assertEquals(24, service.customHeartRateTimeSeries("account-a",
                java.time.LocalDate.parse("2026-11-05"), java.time.LocalDate.parse("2026-11-05")).buckets().size());
        assertEquals(720, service.customHeartRateTimeSeries("account-a",
                java.time.LocalDate.parse("2026-10-08"), java.time.LocalDate.parse("2026-11-06")).buckets().size());

        assertEquals("INVALID_DATE_RANGE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.customHeartRateTimeSeries("account-a", null, java.time.LocalDate.parse("2026-11-06")))
                .getCode());
        assertEquals("INVALID_DATE_RANGE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.customHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-10-07"),
                        java.time.LocalDate.parse("2026-11-06"))).getCode());
        assertEquals("INVALID_DATE_RANGE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.customHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-11-06"),
                        java.time.LocalDate.parse("2026-11-05"))).getCode());
        assertEquals("INVALID_DATE_RANGE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.customHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-11-06"),
                        java.time.LocalDate.parse("2026-11-07"))).getCode());
    }

    @Test
    void rejectsLocalDayWithHistoricalTimezoneTransition() {
        assertEquals("INVALID_REPORT_DATE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.dailyHeartRateTimeSeries("account-a", java.time.LocalDate.parse("1975-06-12")))
                .getCode());
        verifyNoInteractions(heartRateService);
    }

    @Test
    void rejectsFutureDailyDatesAndInvalidWeeklyStartsBeforeQuerying() {
        assertEquals("INVALID_REPORT_DATE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.dailyHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-11-07")))
                .getCode());
        assertEquals("INVALID_WEEK_START_DATE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.weeklyHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-11-03")))
                .getCode());
        assertEquals("INVALID_WEEK_START_DATE", assertThrows(InvalidStatisticsRequestException.class,
                () -> service.weeklyHeartRateTimeSeries("account-a", java.time.LocalDate.parse("2026-11-09")))
                .getCode());
        verifyNoInteractions(heartRateService);
    }

    @Test
    void dailyStepsCalculatesProgressFromSelectedDateGoalAndAllowsOverOneHundredPercent() {
        when(stepsService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of(
                new com.vitaloom.backend.steps.contract.dto.StepSample(5, Instant.parse("2026-11-06T01:00:00Z")),
                new com.vitaloom.backend.steps.contract.dto.StepSample(5, Instant.parse("2026-11-06T02:00:00Z"))));
        when(stepGoalReadQuery.findEffectiveGoal("account-a", java.time.LocalDate.parse("2026-11-06")))
                .thenReturn(Optional.of(new EffectiveStepGoal(5, "2026-11-06")));

        DailyStepsResponse response = service.dailyStepsTimeSeries("account-a", "2026-11-06");

        assertEquals(10, response.totalSteps());
        assertEquals(new DailyStepsResponse.Goal(5, "2026-11-06"), response.goal());
        assertEquals(200.0, response.progressPercent());
        verify(stepGoalReadQuery).findEffectiveGoal("account-a", java.time.LocalDate.parse("2026-11-06"));
    }

    @Test
    void dailyStepsKeepsGoalAndProgressNullWhenEitherSideHasNoData() {
        when(stepsService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of());
        DailyStepsResponse noSteps = service.dailyStepsTimeSeries("account-a", null);
        assertNull(noSteps.totalSteps());
        assertNull(noSteps.goal());
        assertNull(noSteps.progressPercent());

        when(stepsService.findSamplesBetween(eq("account-a"), any(), any())).thenReturn(List.of(
                new com.vitaloom.backend.steps.contract.dto.StepSample(2, Instant.parse("2026-11-06T01:00:00Z"))));
        DailyStepsResponse noGoal = service.dailyStepsTimeSeries("account-a", null);
        assertEquals(2, noGoal.totalSteps());
        assertNull(noGoal.goal());
        assertNull(noGoal.progressPercent());
    }
}
