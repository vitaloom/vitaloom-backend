package com.vitaloom.backend.statistics.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public interface SleepRecentStatisticsService {
    Report recent(String accountId);
    Report recent(String accountId, boolean full, ZoneId selectedZone);

    record Report(Period period, String dataStatus, List<Day> days, Integer recordedDays,
                  int coverageDenominator, Integer averageMinutesPerRecordedDay, List<Insight> insights,
                  List<SleepInsightsStatisticsService.Highlight> highlights) {
        public Report {
            days = List.copyOf(days);
            insights = List.copyOf(insights);
            highlights = List.copyOf(highlights);
        }
    }

    record Period(LocalDate start, LocalDate end, String timeZone) {}
    record Day(LocalDate day, String status, Integer minutes) {}
    record Insight(String code, String status, int sampleDays, int denominatorDays) {}
}
