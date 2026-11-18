package com.vitaloom.backend.assistant;

import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

public final class AssistantPersonalDataService {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SleepRecentStatisticsService sleep;
    private final WaterService water;
    private final Clock clock;

    AssistantPersonalDataService(SleepRecentStatisticsService sleep, WaterService water, Clock clock) {
        this.sleep = sleep;
        this.water = water;
        this.clock = clock;
    }

    SleepSummary recentMainSleep(String owner) {
        var report = sleep.recent(owner);
        return new SleepSummary(report.dataStatus(), report.period().start(), report.period().end(),
                report.recordedDays(), report.coverageDenominator(), report.averageMinutesPerRecordedDay());
    }

    WaterDay waterDay(String owner, String dateText) {
        LocalDate today = today();
        LocalDate date = parseDate(dateText);
        if (date.isAfter(today)) throw new IllegalArgumentException("Future Water dates are not supported");
        var report = water.dailySummary(owner, date.toString(), ZONE.getId());
        return new WaterDay(report.logs().isEmpty() ? "NO_DATA" : "PARTIAL", date,
                report.totalMl(), report.logs().size());
    }

    WaterWeek waterWeek(String owner, String weekStartText) {
        LocalDate today = today();
        LocalDate monday = parseDate(weekStartText);
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY || monday.isAfter(currentMonday)) {
            throw new IllegalArgumentException("Water week must start on a current or past Monday");
        }
        var report = water.weeklyProgress(owner, monday.toString(), ZONE.getId());
        List<WaterDayTotal> elapsedDays = report.days().stream()
                .filter(day -> !LocalDate.parse(day.date()).isAfter(today))
                .map(day -> new WaterDayTotal(LocalDate.parse(day.date()), day.totalMl())).toList();
        int totalMl = elapsedDays.stream().mapToInt(WaterDayTotal::recordedMl).sum();
        int daysWithLogs = (int) elapsedDays.stream().filter(day -> day.recordedMl() > 0).count();
        return new WaterWeek("PARTIAL", monday, elapsedDays.getLast().date(), totalMl, daysWithLogs, elapsedDays);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private static LocalDate parseDate(String text) {
        LocalDate date = LocalDate.parse(text);
        if (!date.toString().equals(text)) throw new IllegalArgumentException("Date must use ISO format");
        return date;
    }

    public record SleepSummary(String status, LocalDate from, LocalDate to, Integer recordedDays,
                               int windowDays, Integer averageMinutesPerRecordedDay) {}
    public record WaterDay(String status, LocalDate date, int recordedMl, int recordCount) {}
    public record WaterWeek(String status, LocalDate weekStart, LocalDate asOf, int recordedMl,
                            int daysWithRecords, List<WaterDayTotal> days) {
        public WaterWeek { days = List.copyOf(days); }
    }
    public record WaterDayTotal(LocalDate date, int recordedMl) {}
}
