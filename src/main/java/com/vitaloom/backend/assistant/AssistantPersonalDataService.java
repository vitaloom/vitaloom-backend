package com.vitaloom.backend.assistant;

import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
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

    SleepPeriod sleepPeriod(String owner, boolean weekToDate) {
        var report = sleep.recent(owner);
        LocalDate today = today();
        LocalDate from = weekToDate ? today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : report.period().start();
        LocalDate to = weekToDate ? today : report.period().end();
        int windowDays = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
        if (!"VERIFIED".equals(report.dataStatus())) {
            return new SleepPeriod(report.dataStatus(), from.toString(), to.toString(), windowDays,
                    null, null, List.of());
        }
        var days = report.days().stream().filter(day -> !day.day().isBefore(from) && !day.day().isAfter(to))
                .sorted(Comparator.comparing(SleepRecentStatisticsService.Day::day))
                .map(day -> new SleepDay(day.status(), day.day().toString(),
                        "RECORDED".equals(day.status()) ? day.minutes() : null)).toList();
        var recorded = days.stream().filter(day -> day.minutes() != null).toList();
        int count = recorded.size();
        Integer average = count == 0 ? null : weekToDate
                ? Math.toIntExact(Math.round(recorded.stream().mapToInt(SleepDay::minutes).average().orElseThrow()))
                : report.averageMinutesPerRecordedDay();
        String status = count > 0 ? "VERIFIED" : days.stream().allMatch(day -> "NOT_RECORDED".equals(day.status()))
                ? "NO_DATA" : "UNVERIFIED_DATA";
        return new SleepPeriod(status, from.toString(), to.toString(), windowDays, count, average, days);
    }

    static boolean verifiedMainSleep(List<SessionResponse> records) {
        if (records.size() != 1) return false;
        var row = records.getFirst();
        if (row.confirmedAt() == null || row.minutes() <= 0 || row.minutes() > 1440) return false;
        if ("DURATION".equals(row.method())) return true;
        if (!"RANGE".equals(row.method()) || row.startAt() == null || row.endAt() == null
                || row.timeZone() == null
                || !Duration.between(row.startAt(), row.endAt()).equals(Duration.ofMinutes(row.minutes()))) {
            return false;
        }
        try {
            ZoneId.of(row.timeZone());
            return true;
        } catch (DateTimeException invalidZone) {
            return false;
        }
    }

    WaterDay waterDay(String owner, String dateText, boolean includeEntries) {
        LocalDate today = today();
        LocalDate date = parseDate(dateText);
        if (date.isAfter(today)) throw new IllegalArgumentException("Future Water dates are not supported");
        var report = water.dailySummary(owner, date.toString(), ZONE.getId());
        var entries = includeEntries ? report.logs().stream().limit(50)
                .map(log -> new WaterEntry(log.amountMl(), log.loggedAt().atZone(ZONE).toOffsetDateTime().toString()))
                .toList() : List.<WaterEntry>of();
        return new WaterDay(report.logs().isEmpty() ? "NO_DATA" : "PARTIAL", date,
                report.totalMl(), report.logs().size(), entries);
    }

    WaterWeek waterWeek(String owner, String weekStartText) {
        LocalDate today = today();
        LocalDate monday = parseDate(weekStartText);
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (!DayOfWeek.MONDAY.equals(monday.getDayOfWeek()) || monday.isAfter(currentMonday)) {
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

    public record SleepPeriod(String status, String from, String to, int windowDays, Integer recordedDays,
            Integer averageMinutesPerRecordedDay, List<SleepDay> days) {}
    public record SleepDay(String status, String date, Integer minutes) {}
    public record WaterDay(String status, LocalDate date, int recordedMl, int recordCount, List<WaterEntry> entries) {}
    public record WaterEntry(int amountMl, String recordedAt) {}
    public record WaterWeek(String status, LocalDate weekStart, LocalDate asOf, int recordedMl,
                            int daysWithRecords, List<WaterDayTotal> days) {
        public WaterWeek { days = List.copyOf(days); }
    }
    public record WaterDayTotal(LocalDate date, int recordedMl) {}
}
