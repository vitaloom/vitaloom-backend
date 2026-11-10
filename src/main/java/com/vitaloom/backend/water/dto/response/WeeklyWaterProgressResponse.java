package com.vitaloom.backend.water.dto.response;

import java.util.List;

public record WeeklyWaterProgressResponse(String weekStart, String weekEnd, List<Day> days) {
    public record Day(String date, int totalMl, int goalMl) {}
}
