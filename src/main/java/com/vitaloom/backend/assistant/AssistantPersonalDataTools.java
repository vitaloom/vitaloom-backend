package com.vitaloom.backend.assistant;

import java.util.function.BooleanSupplier;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class AssistantPersonalDataTools {
    private final String owner;
    private final AssistantPersonalDataService data;
    private final BooleanSupplier authorized;
    private final SleepInsightsStatisticsService insights;
    private final ZoneId timingZone;
    private int invocations;
    private String status;
    private String text;
    private AssistantCourseAnswers.Source source;

    AssistantPersonalDataTools(String owner, AssistantPersonalDataService data, BooleanSupplier authorized,
            SleepInsightsStatisticsService insights, ZoneId timingZone) {
        this.owner = owner;
        this.data = data;
        this.authorized = authorized;
        this.insights = insights;
        this.timingZone = timingZone;
    }

    String timingToolName() {
        return timingZone == null ? null : "getRecentMainSleepTiming";
    }

    @Tool(name = "getRecentMainSleepTiming", description = "Read verified A04 historical MAIN sleep bedtime and wake-time ranges for the explicitly selected reference zone. Use for bedtime or wake-time questions. Descriptive only.")
    public String getRecentMainSleepTiming() {
        if (timingZone == null || insights == null) throw new IllegalStateException("Reference zone required");
        authorize();
        var detail = insights.detail(owner, "A04", timingZone);
        status = detail.status();
        var period = detail.period();
        source = new AssistantCourseAnswers.Source("sleep-a04", 1, "Giấc ngủ gần đây (A04)",
                "/sleep/recent", period.start().toString(), period.end().toString());
        switch (status) {
            case "READY" -> {
                text = "Từ %s đến %s (%s), trên %s ngày có bản ghi giấc ngủ chính, bạn bắt đầu ngủ trong khoảng %s và thức dậy trong khoảng %s. Đây là khung giờ đã ghi nhận để bạn tham khảo hôm nay."
                        .formatted(period.start(), period.end(), timingZone, detail.sample().get("eligibleRangeDays"),
                                range(detail.evidence(), "bedtime"), range(detail.evidence(), "wake"));
                status = "ANSWERED";
            }
            case "INSUFFICIENT_DATA" -> {
                status = "INSUFFICIENT_EVIDENCE";
                text = "A04 cần ít nhất 3 ngày giấc ngủ chính có giờ bắt đầu và kết thúc hợp lệ trong múi giờ "
                        + timingZone + ", và áp dụng điều kiện về đổi giờ theo mùa.";
            }
            default -> { }
        }
        return text == null ? status : text;
    }

    private static String range(Map<String, Object> evidence, String key) {
        var limits = (Map<?, ?>) evidence.get(key);
        return clockTime((int) limits.get("minMinute")) + " đến " + clockTime((int) limits.get("maxMinute"));
    }

    private static String clockTime(int minute) {
        int day = Math.floorDiv(minute, 1440);
        String time = LocalTime.ofSecondOfDay(Math.floorMod(minute, 1440) * 60L).toString();
        return day == 0 ? time : time + " (lệch " + day + " ngày so với ngày thức dậy)";
    }

    @Tool(name = "getRecentMainSleepSummary", description = "Read the authenticated user's verified rolling seven-day MAIN sleep average. Use for questions about average MAIN sleep duration, recent sleep totals, or sleep records this week. This reports only recorded facts and does not recommend bedtimes or wake times.")
    public AssistantPersonalDataService.SleepSummary getRecentMainSleepSummary() {
        authorize();
        var result = data.recentMainSleep(owner);
        status = switch (result.status()) {
            case "VERIFIED" -> result.averageMinutesPerRecordedDay() == null ? "NO_DATA" : "ANSWERED";
            case "NO_DATA" -> "NO_DATA";
            default -> "UNVERIFIED_DATA";
        };
        source = new AssistantCourseAnswers.Source("sleep-recent", 1, "Giấc ngủ gần đây",
                "/sleep/recent", result.from().toString(), result.to().toString());
        if ("ANSWERED".equals(status)) {
            text = "Từ %s đến %s, giấc ngủ chính trung bình %d phút trên %d/%d ngày có bản ghi."
                    .formatted(result.from(), result.to(), result.averageMinutesPerRecordedDay(),
                            result.recordedDays(), result.windowDays());
        }
        return result;
    }

    @Tool(name = "getWaterDailyProgress", description = "Read the authenticated user's saved Water intake total for one local calendar date. Use for questions about today's or another specified date's recorded water logs. Report saved server logs only; do not describe the total as a complete intake or medical target.")
    public AssistantPersonalDataService.WaterDay getWaterDailyProgress(
            @ToolParam(description = "The requested local date in YYYY-MM-DD format. Use today's date when the user asks about today.") String date) {
        authorize();
        var result = data.waterDay(owner, date);
        status = result.status();
        if (!"NO_DATA".equals(status)) {
            text = "Ngày %s có %d ml nước từ %d lần uống đã lưu."
                    .formatted(result.date(), result.recordedMl(), result.recordCount());
        }
        source = new AssistantCourseAnswers.Source("water-day", 1, "Nhật ký nước",
                "/water/progress", result.date().toString(), result.date().toString());
        return result;
    }

    @Tool(name = "getWaterWeeklyProgress", description = "Read the authenticated user's saved Water totals for the elapsed days of one Monday-to-Sunday week. Use for this week or a past week. Only dates through today are returned. These are server-recorded logs, not a verified total of all water consumed.")
    public AssistantPersonalDataService.WaterWeek getWaterWeeklyProgress(
            @ToolParam(description = "The Monday starting the requested week in YYYY-MM-DD format. Use the current week's Monday when the user asks about this week.") String weekStart) {
        authorize();
        var result = data.waterWeek(owner, weekStart);
        status = result.status();
        if (result.daysWithRecords() == 0) status = "NO_DATA";
        else text = "Từ %s đến %s có %d ml nước đã lưu, trên %d ngày có bản ghi."
                .formatted(result.weekStart(), result.asOf(), result.recordedMl(), result.daysWithRecords());
        source = new AssistantCourseAnswers.Source("water-week", 1, "Tiến độ nước",
                "/water/progress", result.weekStart().toString(), result.asOf().toString());
        return result;
    }

    AssistantCourseAnswers.Answer answer() {
        if (status == null) return null;
        String message = text != null ? text : "NO_DATA".equals(status)
                ? "Chưa có bản ghi trong khoảng này." : "Chưa thể xác minh dữ liệu trong khoảng này.";
        return new AssistantCourseAnswers.Answer(status,
                java.util.List.of(new AssistantCourseAnswers.Part(status, message, source)), null);
    }

    private void authorize() {
        if (++invocations > 1) throw new IllegalStateException("Assistant personal-data tool budget exhausted");
        if (!authorized.getAsBoolean()) throw new PersonalDataAccessDeniedException();
    }

    static final class PersonalDataAccessDeniedException extends RuntimeException {}
}
