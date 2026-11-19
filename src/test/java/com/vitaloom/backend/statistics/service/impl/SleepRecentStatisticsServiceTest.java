package com.vitaloom.backend.statistics.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService.Report;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SleepRecentStatisticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T16:59:59Z");
    private final SleepMainReadQuery query = mock(SleepMainReadQuery.class);
    private final SleepInsightsStatisticsService insights = mock(SleepInsightsStatisticsService.class);
    private final SleepRecentStatisticsServiceImpl service = new SleepRecentStatisticsServiceImpl(
            query, Clock.fixed(NOW, ZoneOffset.UTC), insights);

    @Test
    void emptyWindowHasSevenNullDaysAndNullAverage() {
        when(query.readWindow("account-a", LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-09")))
                .thenReturn(new MainSleepWindow(true, List.of()));

        Report response = service.recent("account-a");

        assertEquals("NO_DATA", response.dataStatus());
        assertEquals(7, response.days().size());
        assertTrue(response.days().stream().allMatch(day ->
                day.status().equals("NOT_RECORDED") && day.minutes() == null));
        assertEquals(0, response.recordedDays());
        assertEquals(7, response.coverageDenominator());
        assertNull(response.averageMinutesPerRecordedDay());
        assertTrue(response.insights().isEmpty());
    }

    @Test
    void recentDaysIncludeTodayAtVietnamMidnightAndAverageOnlyRecordedDays() {
        when(query.readWindow("account-a", LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-09")))
                .thenReturn(new MainSleepWindow(true, List.of(
                        new MainSleepDay("one", LocalDate.parse("2026-11-03"), 420),
                        new MainSleepDay("two", LocalDate.parse("2026-11-06"), 450),
                        new MainSleepDay("three", LocalDate.parse("2026-11-09"), 451))));

        Report response = service.recent("account-a");

        assertEquals("2026-11-03", response.period().start().toString());
        assertEquals("2026-11-09", response.period().end().toString());
        assertEquals("VERIFIED", response.dataStatus());
        assertEquals(List.of("2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06",
                        "2026-11-07", "2026-11-08", "2026-11-09"),
                response.days().stream().map(day -> day.day().toString()).toList());
        assertNull(response.days().get(1).minutes());
        assertEquals(3, response.recordedDays());
        assertEquals(440, response.averageMinutesPerRecordedDay());
        assertEquals("A01", response.insights().getFirst().code());
        assertEquals(3, response.insights().getFirst().sampleDays());
        assertEquals(7, response.insights().getFirst().denominatorDays());
        verify(insights).recentHighlights(eq("account-a"),
                eq(new SleepInsightsStatisticsService.Period(LocalDate.parse("2026-11-03"),
                        LocalDate.parse("2026-11-09"), "Asia/Ho_Chi_Minh")), any());
    }

    @Test
    void twoRecordedDaysDoNotPublishA01AndHalfMinuteRoundsUp() {
        when(query.readWindow(any(), any(), any())).thenReturn(new MainSleepWindow(true, List.of(
                new MainSleepDay("one", LocalDate.parse("2026-11-03"), 420),
                new MainSleepDay("two", LocalDate.parse("2026-11-04"), 451))));

        Report response = service.recent("account-a");

        assertEquals(436, response.averageMinutesPerRecordedDay());
        assertTrue(response.insights().isEmpty());
        assertTrue(response.highlights().isEmpty());
    }

    @Test
    void oneRecordedDayKeepsItsValueAndDoesNotPublishA01() {
        when(query.readWindow(any(), any(), any())).thenReturn(new MainSleepWindow(true, List.of(
                new MainSleepDay("one", LocalDate.parse("2026-11-03"), 420))));

        Report response = service.recent("account-a");

        assertEquals(1, response.recordedDays());
        assertEquals(420, response.averageMinutesPerRecordedDay());
        assertTrue(response.insights().isEmpty());
    }

    @Test
    void unverifiedWindowSuppressesAllAggregatesAndMarksMissingDaysUnverified() {
        when(query.readWindow(any(), any(), any())).thenReturn(new MainSleepWindow(false, List.of(
                new MainSleepDay("one", LocalDate.parse("2026-11-03"), 420))));

        Report response = service.recent("account-a");

        assertEquals("UNVERIFIED_DATA", response.dataStatus());
        assertNull(response.recordedDays());
        assertNull(response.averageMinutesPerRecordedDay());
        assertTrue(response.insights().isEmpty());
        assertTrue(response.highlights().isEmpty());
        verifyNoInteractions(insights);
        assertEquals("RECORDED", response.days().getFirst().status());
        assertEquals("UNVERIFIED", response.days().getLast().status());
    }

    @Test
    void fullWeekPublishesOneA01() {
        when(query.readWindow(any(), any(), any())).thenReturn(new MainSleepWindow(true, List.of(
                new MainSleepDay("1", LocalDate.parse("2026-11-03"), 1),
                new MainSleepDay("2", LocalDate.parse("2026-11-04"), 2),
                new MainSleepDay("3", LocalDate.parse("2026-11-05"), 3),
                new MainSleepDay("4", LocalDate.parse("2026-11-06"), 4),
                new MainSleepDay("5", LocalDate.parse("2026-11-07"), 5),
                new MainSleepDay("6", LocalDate.parse("2026-11-08"), 6),
                new MainSleepDay("7", LocalDate.parse("2026-11-09"), 7))));

        Report response = service.recent("account-a");

        assertEquals(7, response.recordedDays());
        assertEquals(4, response.averageMinutesPerRecordedDay());
        assertEquals(1, response.insights().size());
        verify(query).readWindow("account-a", LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-09"));
    }

    @Test
    void dateRollsOverAtVietnamMidnightAndAcrossMonthBoundary() {
        when(query.readWindow(any(), any(), any())).thenReturn(new MainSleepWindow(true, List.of()));
        SleepRecentStatisticsServiceImpl nextDate = new SleepRecentStatisticsServiceImpl(query,
                Clock.fixed(Instant.parse("2026-11-09T17:00:00Z"), ZoneOffset.UTC), insights);

        nextDate.recent("account-a");

        verify(query).readWindow("account-a", LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-10"));

        SleepRecentStatisticsServiceImpl monthEnd = new SleepRecentStatisticsServiceImpl(query,
                Clock.fixed(Instant.parse("2026-11-30T17:00:00Z"), ZoneOffset.UTC), insights);
        monthEnd.recent("account-a");
        verify(query).readWindow("account-a", LocalDate.parse("2026-11-25"), LocalDate.parse("2026-12-01"));
    }
}
