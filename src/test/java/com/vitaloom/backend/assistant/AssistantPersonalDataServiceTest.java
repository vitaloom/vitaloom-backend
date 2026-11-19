package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.dto.response.WeeklyWaterProgressResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantPersonalDataServiceTest {
    private static final String OWNER = "synthetic-owner";
    private static final LocalDate TODAY = LocalDate.parse("2026-11-18");
    private static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
    private final SleepRecentStatisticsService sleep = mock(SleepRecentStatisticsService.class);
    private final WaterService water = mock(WaterService.class);
    private final AssistantPersonalDataService service = new AssistantPersonalDataService(sleep, water,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void currentWeekUsesHoChiMinhMondayThroughTodayAndRoundsRecordedAverage() {
        when(sleep.recent(OWNER)).thenReturn(report("VERIFIED", List.of(
                day("2026-11-15", "RECORDED", 500), day("2026-11-16", "RECORDED", 401),
                day("2026-11-17", "NOT_RECORDED", null), day("2026-11-18", "RECORDED", 500),
                day("2026-11-19", "RECORDED", 1000)), 777));

        var result = service.sleepPeriod(OWNER, true);

        assertThat(result.from()).isEqualTo("2026-11-16");
        assertThat(result.to()).isEqualTo(TODAY.toString());
        assertThat(result.windowDays()).isEqualTo(3);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.recordedDays()).isEqualTo(2);
        assertThat(result.averageMinutesPerRecordedDay()).isEqualTo(451);
        assertThat(result.days()).extracting(AssistantPersonalDataService.SleepDay::date)
                .containsExactly("2026-11-16", "2026-11-17", "2026-11-18");
        assertThat(result.days().get(1).minutes()).isNull();
    }

    @Test
    void nonVerifiedSleepStatusReturnsNoDayDetails() {
        when(sleep.recent(OWNER)).thenReturn(report("UNVERIFIED_DATA", List.of(day("2026-11-18", "RECORDED", 420)), 420));

        var result = service.sleepPeriod(OWNER, false);

        assertThat(result.status()).isEqualTo("UNVERIFIED_DATA");
        assertThat(result.recordedDays()).isNull();
        assertThat(result.days()).isEmpty();
    }

    @Test
    void recentWindowPreservesServerAverageAndSortsOnlyDaysInsideTheReportedPeriod() {
        when(sleep.recent(OWNER)).thenReturn(report("VERIFIED", List.of(
                day("2026-11-18", "RECORDED", 480), day("2026-11-11", "RECORDED", 300),
                day("2026-11-12", "NOT_RECORDED", null), day("2026-11-15", "RECORDED", 420)), 455));

        var result = service.sleepPeriod(OWNER, false);

        assertThat(result.from()).isEqualTo("2026-11-12");
        assertThat(result.to()).isEqualTo("2026-11-18");
        assertThat(result.windowDays()).isEqualTo(7);
        assertThat(result.recordedDays()).isEqualTo(2);
        assertThat(result.averageMinutesPerRecordedDay()).isEqualTo(455);
        assertThat(result.days()).extracting(AssistantPersonalDataService.SleepDay::date)
                .containsExactly("2026-11-12", "2026-11-15", "2026-11-18");
    }

    @Test
    void verifiedWindowDistinguishesNoRecordedDaysFromMixedUnverifiedDays() {
        when(sleep.recent(OWNER)).thenReturn(report("VERIFIED", List.of(), null));
        var noData = service.sleepPeriod(OWNER, false);
        when(sleep.recent(OWNER)).thenReturn(report("VERIFIED", List.of(
                day("2026-11-12", "NOT_RECORDED", null), day("2026-11-13", "UNVERIFIED", null)), null));

        var mixed = service.sleepPeriod(OWNER, false);

        assertThat(noData.status()).isEqualTo("NO_DATA");
        assertThat(noData.recordedDays()).isZero();
        assertThat(noData.averageMinutesPerRecordedDay()).isNull();
        assertThat(mixed.status()).isEqualTo("UNVERIFIED_DATA");
        assertThat(mixed.days()).extracting(AssistantPersonalDataService.SleepDay::minutes)
                .containsOnlyNulls();
    }

    @Test
    void waterDayOmitsEntriesUnlessRequestedAndLimitsRequestedEntriesToFifty() {
        var logs = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new WaterLogResponse("synthetic-" + i, 100 + i, NOW.plusSeconds(i))).toList();
        when(water.dailySummary(OWNER, TODAY.toString(), "Asia/Ho_Chi_Minh"))
                .thenReturn(new DailyWaterSummaryResponse(TODAY.toString(), 2000, 6375, 3.1875, logs));

        var withoutEntries = service.waterDay(OWNER, TODAY.toString(), false);
        var withEntries = service.waterDay(OWNER, TODAY.toString(), true);

        assertThat(withoutEntries.status()).isEqualTo("PARTIAL");
        assertThat(withoutEntries.recordCount()).isEqualTo(51);
        assertThat(withoutEntries.entries()).isEmpty();
        assertThat(withEntries.entries()).hasSize(50);
        var recordedAt = java.time.OffsetDateTime.parse(withEntries.entries().getFirst().recordedAt());
        assertThat(recordedAt.toInstant()).isEqualTo(NOW);
        assertThat(recordedAt.getOffset()).isEqualTo(ZoneOffset.ofHours(7));
    }

    @Test
    void waterDayWithoutLogsIsNoDataAndFutureOrNonIsoDatesAreRejectedBeforeLookup() {
        when(water.dailySummary(OWNER, TODAY.toString(), "Asia/Ho_Chi_Minh"))
                .thenReturn(new DailyWaterSummaryResponse(TODAY.toString(), 2000, 0, 0, List.of()));

        assertThat(service.waterDay(OWNER, TODAY.toString(), false).status()).isEqualTo("NO_DATA");
        assertThatThrownBy(() -> service.waterDay(OWNER, "2026-11-19", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.waterDay(OWNER, "2026-1-8", false))
                .isInstanceOf(RuntimeException.class);
        verify(water).dailySummary(OWNER, TODAY.toString(), "Asia/Ho_Chi_Minh");
    }

    @Test
    void waterWeekIncludesOnlyElapsedDaysAndRejectsInvalidOrFutureWeeksBeforeLookup() {
        when(water.weeklyProgress(OWNER, "2026-11-16", "Asia/Ho_Chi_Minh")).thenReturn(
                new WeeklyWaterProgressResponse("2026-11-16", "2026-11-22", List.of(
                        new WeeklyWaterProgressResponse.Day("2026-11-16", 500, 2000),
                        new WeeklyWaterProgressResponse.Day("2026-11-17", 0, 2000),
                        new WeeklyWaterProgressResponse.Day("2026-11-18", 750, 2000),
                        new WeeklyWaterProgressResponse.Day("2026-11-19", 900, 2000))));

        var result = service.waterWeek(OWNER, "2026-11-16");

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.asOf()).isEqualTo(TODAY);
        assertThat(result.recordedMl()).isEqualTo(1250);
        assertThat(result.daysWithRecords()).isEqualTo(2);
        assertThat(result.days()).hasSize(3);
        assertThatThrownBy(() -> service.waterWeek(OWNER, "2026-11-17"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.waterWeek(OWNER, "2026-11-23"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(water).weeklyProgress(OWNER, "2026-11-16", "Asia/Ho_Chi_Minh");
    }

    @Test
    void verifiedMainSleepRequiresExactlyOneConfirmedValidRecord() {
        var validDuration = session("DURATION", 420, null, null, "UTC", NOW);
        var validRange = session("RANGE", 420, NOW, NOW.plusSeconds(420 * 60L), "UTC", NOW);
        var invalidRange = session("RANGE", 419, NOW, NOW.plusSeconds(420 * 60L), "UTC", NOW);
        var missingRangeStart = session("RANGE", 420, null, NOW, "UTC", NOW);
        var invalidRangeZone = session("RANGE", 420, NOW, NOW.plusSeconds(420 * 60L), "Not/AZone", NOW);
        var unsupportedMethod = session("OTHER", 420, null, null, "UTC", NOW);
        var excessiveDuration = session("DURATION", 1441, null, null, "UTC", NOW);
        var missingConfirmation = session("DURATION", 420, null, null, "UTC", null);

        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(validDuration))).isTrue();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(validRange))).isTrue();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of())).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(validDuration, validDuration))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(invalidRange))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(missingRangeStart))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(invalidRangeZone))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(unsupportedMethod))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(excessiveDuration))).isFalse();
        assertThat(AssistantPersonalDataService.verifiedMainSleep(List.of(missingConfirmation))).isFalse();
    }

    private static SleepRecentStatisticsService.Report report(String status,
            List<SleepRecentStatisticsService.Day> days, Integer average) {
        return new SleepRecentStatisticsService.Report(
                new SleepRecentStatisticsService.Period(LocalDate.parse("2026-11-12"), TODAY, "Asia/Ho_Chi_Minh"),
                status, days, null, 7, average, List.of(), List.of());
    }

    private static SleepRecentStatisticsService.Day day(String date, String status, Integer minutes) {
        return new SleepRecentStatisticsService.Day(LocalDate.parse(date), status, minutes);
    }

    private static SessionResponse session(String method, int minutes, Instant start, Instant end, String zone,
            Instant confirmedAt) {
        return new SessionResponse("synthetic", confirmedAt, "MAIN", method, null, TODAY.toString(), minutes,
                zone, null, null, start, end, false);
    }
}
