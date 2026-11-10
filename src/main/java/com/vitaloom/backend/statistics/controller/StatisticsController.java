package com.vitaloom.backend.statistics.controller;

import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.service.StatisticsService;
import com.vitaloom.backend.statistics.dto.response.StepsTimeSeriesResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/heart-rate/summary")
    public HeartRateSummaryResponse heartRateSummary(
            Authentication authentication,
            @RequestParam(required = false) String date) {
        return service.heartRateSummary(authentication.getName(), date);
    }

    @GetMapping("/steps/daily")
    public StepsTimeSeriesResponse dailySteps(Authentication authentication,
                                               @RequestParam(required = false) String date) {
        return service.dailyStepsTimeSeries(authentication.getName(), date);
    }

    @GetMapping("/steps/weekly")
    public StepsTimeSeriesResponse weeklySteps(Authentication authentication,
                                                @RequestParam(required = false) String weekStartDate) {
        return service.weeklyStepsTimeSeries(authentication.getName(), weekStartDate);
    }

    @GetMapping("/steps/custom")
    public StepsTimeSeriesResponse customSteps(Authentication authentication,
                                                @RequestParam String startDate,
                                                @RequestParam String endDate) {
        return service.customStepsTimeSeries(authentication.getName(), startDate, endDate);
    }
}
