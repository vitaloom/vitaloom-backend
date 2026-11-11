package com.vitaloom.backend.water.service;

import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.dto.response.WeeklyWaterProgressResponse;

public interface WaterService {
    int DEFAULT_GOAL_ML = 2000;

    DailyWaterSummaryResponse dailySummary(String userId, String dateText, String timeZone);
    WeeklyWaterProgressResponse weeklyProgress(String userId, String weekStartText, String timeZone);
    WaterLogResponse create(String userId, Integer amountMl, String loggedAtText);
}
