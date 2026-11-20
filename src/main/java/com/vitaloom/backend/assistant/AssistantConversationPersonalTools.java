package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

final class AssistantConversationPersonalTools {
    private final String owner;
    private final AssistantPersonalDataService data;
    private final SleepJournalService journal;
    private final SleepInsightsStatisticsService insights;
    private final AssistantConversationTools evidence;
    private final boolean allowed;
    private final BooleanSupplier current;

    AssistantConversationPersonalTools(String owner, AssistantPersonalDataService data, SleepJournalService journal,
            SleepInsightsStatisticsService insights, AssistantConversationTools evidence, boolean allowed,
            BooleanSupplier current) {
        this.owner = owner;
        this.data = data;
        this.journal = journal;
        this.insights = insights;
        this.evidence = evidence;
        this.allowed = allowed;
        this.current = current;
    }

    @Tool(description = "Read verified MAIN sleep duration and coverage for either the rolling recent seven-day "
            + "window or the current Monday-to-today week. Includes dated recorded durations for descriptive trends. "
            + "Missing or unverified days have no minutes.")
    public Result readSleepPeriod(
            @ToolParam(description = "RECENT_SEVEN_DAYS or THIS_WEEK") SleepWindow window) {
        if (window == null) return Result.status("INVALID_ARGUMENTS");
        return read(() -> {
            boolean thisWeek = window == SleepWindow.THIS_WEEK;
            var report = data.sleepPeriod(owner, thisWeek);
            var source = source(thisWeek ? "sleep-week-to-date" : "sleep-recent",
                    thisWeek ? "Giấc ngủ trong tuần này" : "Giấc ngủ gần đây", "/sleep/recent",
                    report.from(), report.to());
            return publish(report.status(), source, report);
        });
    }

    @Tool(description = "Read verified MAIN sleep for one calendar date. Request timing only when the question needs "
            + "bedtime or wake time. Does not assess sleep quality.")
    public Result readSleepDay(@ToolParam(description = "Local calendar date, YYYY-MM-DD") String date,
            @ToolParam(required = false, description = "Include verified start and end times when available")
            Boolean includeTiming) {
        return read(() -> {
            LocalDate day = requireDate(date);
            var records = journal.list(owner, date, date).stream().filter(row -> "MAIN".equals(row.sleepType()))
                    .toList();
            boolean verified = AssistantPersonalDataService.verifiedMainSleep(records);
            String status = verified ? "VERIFIED" : records.isEmpty() ? "NO_DATA" : "UNVERIFIED_DATA";
            var source = source("sleep-day", "Nhật ký giấc ngủ", "/sleep/journal", date, date);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("date", day.toString());
            if (verified) {
                var record = records.getFirst();
                facts.put("minutes", record.minutes());
                if (Boolean.TRUE.equals(includeTiming) && "RANGE".equals(record.method())) {
                    var zone = ZoneId.of(record.timeZone());
                    facts.put("startedAt", record.startAt().atZone(zone).toOffsetDateTime().toString());
                    facts.put("endedAt", record.endAt().atZone(zone).toOffsetDateTime().toString());
                }
            }
            return publish(status, source, facts);
        });
    }

    @Tool(description = "Read verified historical MAIN sleep timing evidence, including bedtime and wake-time "
            + "ranges when available. Use it to describe regularity of recorded times. It gives no prescribed times.")
    public Result readRecentSleepTiming() {
        return read(() -> {
            if (insights == null) return Result.status("UNAVAILABLE");
            var zone = AssistantPersonalDataService.ZONE;
            var detail = insights.detail(owner, "A04", zone);
            var from = detail.period().start().toString();
            var to = detail.period().end().toString();
            var source = source("sleep-a04", "Giờ ngủ và thức gần đây", "/sleep/recent", from, to);
            Object facts = "READY".equals(detail.status())
                    ? Map.of("timeZone", zone.toString(), "sample", detail.sample(), "timing", detail.evidence())
                    : Map.of("timeZone", zone.toString());
            return publish(detail.status(), source, facts);
        });
    }

    @Tool(description = "Read water intake saved to the server for one calendar date. Includes recorded total and "
            + "log count when available. Coverage is partial and the total is not a personal target.")
    public Result readWaterDay(@ToolParam(description = "Local calendar date, YYYY-MM-DD") String date,
            @ToolParam(required = false, description = "Include individual amounts and times only when requested")
            Boolean includeEntries) {
        return read(() -> {
            requireDate(date);
            var report = data.waterDay(owner, date, Boolean.TRUE.equals(includeEntries));
            var source = source("water-day", "Nhật ký nước", "/water/progress", date, date);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("date", date);
            if (report.recordCount() > 0) {
                facts.put("recordedMl", report.recordedMl());
                facts.put("recordCount", report.recordCount());
                if (Boolean.TRUE.equals(includeEntries)) {
                    facts.put("entries", report.entries());
                    facts.put("entriesTruncated", report.entries().size() < report.recordCount());
                }
            }
            return publish(report.status(), source, facts);
        });
    }

    @Tool(description = "Read water intake saved to the server during a calendar week through today. Reports total "
            + "and days with logs. Coverage is partial and missing days have no amount.")
    public Result readWaterWeek(@ToolParam(description = "Monday of the selected week, YYYY-MM-DD") String monday) {
        return read(() -> {
            requireDate(monday);
            var report = data.waterWeek(owner, monday);
            var source = source("water-week", "Tiến độ nước", "/water/progress", monday, report.asOf().toString());
            List<Map<String, Object>> days = report.days().stream().map(day -> day.recordedMl() > 0
                    ? Map.<String, Object>of("date", day.date().toString(), "recordedMl", day.recordedMl())
                    : Map.<String, Object>of("date", day.date().toString(), "status", "NOT_RECORDED")).toList();
            String status = report.daysWithRecords() == 0 ? "NO_DATA" : "PARTIAL";
            return publish(status, source, new WaterPeriod(days, report.daysWithRecords(),
                    report.daysWithRecords() == 0 ? null : report.recordedMl()));
        });
    }

    private Result read(Supplier<Result> operation) {
        if (!allowed) {
            evidence.markPersonalDenied();
            return Result.status("PERMISSION_REQUIRED");
        }
        requireCurrent();
        try {
            var result = operation.get();
            requireCurrent();
            if ("UNAVAILABLE".equals(result.status())) evidence.markPersonalUnavailable();
            return result;
        } catch (IllegalArgumentException | DateTimeException invalid) {
            return Result.status("INVALID_ARGUMENTS");
        } catch (AssistantNativeChat.AccessChanged changed) {
            throw changed;
        } catch (RuntimeException failure) {
            evidence.markPersonalUnavailable();
            LoggerFactory.getLogger(AssistantConversationPersonalTools.class).atWarn()
                    .addKeyValue("error_type", failure.getClass().getSimpleName())
                    .log("Assistant personal evidence unavailable");
            return Result.status("UNAVAILABLE");
        }
    }

    private void requireCurrent() {
        if (!current.getAsBoolean()) throw new AssistantNativeChat.AccessChanged();
    }

    private LocalDate requireDate(String date) {
        if (date == null) throw new IllegalArgumentException();
        LocalDate value = LocalDate.parse(date);
        if (!value.toString().equals(date) || value.isAfter(data.today())) throw new IllegalArgumentException();
        return value;
    }

    private Result publish(String status, AssistantCourseAnswers.Source source, Object facts) {
        requireCurrent();
        return new Result(status, evidence.addPersonal(status, source, facts, current));
    }

    private static AssistantCourseAnswers.Source source(String id, String title, String route, String from, String to) {
        return new AssistantCourseAnswers.Source(id, 1, title, route, from, to);
    }

    public enum SleepWindow { RECENT_SEVEN_DAYS, THIS_WEEK }

    public record WaterPeriod(List<Map<String, Object>> days, int daysWithRecords, Integer recordedMl) {}

    public record Result(String status, AssistantConversationTools.Evidence evidence) {
        static Result status(String status) {
            return new Result(status, null);
        }
    }
}
