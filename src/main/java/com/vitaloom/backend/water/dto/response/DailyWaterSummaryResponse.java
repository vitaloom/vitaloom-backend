package com.vitaloom.backend.water.dto.response;

import java.util.List;

public record DailyWaterSummaryResponse(String date, int goalMl, int totalMl, double percent,
                                        List<WaterLogResponse> logs) {}
