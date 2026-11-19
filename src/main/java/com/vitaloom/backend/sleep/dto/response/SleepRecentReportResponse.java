package com.vitaloom.backend.sleep.dto.response;

import java.time.LocalDate;
import java.util.List;

public record SleepRecentReportResponse(
        Period period,
        String dataStatus,
        List<Day> days,
        Integer recordedDays,
        int coverageDenominator,
        Integer averageMinutesPerRecordedDay,
        List<Insight> insights,
        List<Highlight> highlights) {
    public SleepRecentReportResponse {
        days = List.copyOf(days);
        insights = List.copyOf(insights);
        highlights = List.copyOf(highlights);
    }

    public record Period(LocalDate start, LocalDate end, String timeZone) {}
    public record Day(LocalDate day, String status, Integer minutes) {}
    public record Insight(String code, String status, int sampleDays, int denominatorDays) {}
    public record Highlight(String code, String status, String title, String summary) {}
}
