package com.vitaloom.backend.config;

import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;
import com.vitaloom.backend.sleep.service.SleepRecentReportService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SleepRecentReportConfig {
    @Bean
    SleepRecentReportService sleepRecentReportService(SleepRecentStatisticsService statistics) {
        return accountId -> toResponse(statistics.recent(accountId));
    }

    private static SleepRecentReportResponse toResponse(SleepRecentStatisticsService.Report report) {
        var period = report.period();
        var days = report.days().stream()
                .map(day -> new SleepRecentReportResponse.Day(day.day(), day.status(), day.minutes()))
                .toList();
        var insights = report.insights().stream()
                .map(insight -> new SleepRecentReportResponse.Insight(
                        insight.code(), insight.status(), insight.sampleDays(), insight.denominatorDays()))
                .toList();
        return new SleepRecentReportResponse(
                new SleepRecentReportResponse.Period(period.start(), period.end(), period.timeZone()),
                report.dataStatus(), days, report.recordedDays(), report.coverageDenominator(),
                report.averageMinutesPerRecordedDay(), insights);
    }
}
