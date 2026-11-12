package com.vitaloom.backend.statistics.service;

import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.dto.response.HeartRateTimeSeriesResponse;
import com.vitaloom.backend.statistics.dto.response.StepsTimeSeriesResponse;
import com.vitaloom.backend.statistics.dto.response.DailyStepsResponse;
import java.time.LocalDate;

public interface StatisticsService {
    HeartRateSummaryResponse heartRateSummary(String accountId, String date);
    HeartRateTimeSeriesResponse dailyHeartRateTimeSeries(String accountId, LocalDate date);
    HeartRateTimeSeriesResponse weeklyHeartRateTimeSeries(String accountId, LocalDate weekStartDate);
    HeartRateTimeSeriesResponse customHeartRateTimeSeries(String accountId, LocalDate startDate, LocalDate endDate);
    DailyStepsResponse dailyStepsTimeSeries(String accountId, String date);
    StepsTimeSeriesResponse weeklyStepsTimeSeries(String accountId, String weekStartDate);
    StepsTimeSeriesResponse customStepsTimeSeries(String accountId, String startDate, String endDate);
}
