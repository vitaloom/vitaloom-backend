package com.vitaloom.backend.statistics.controller;

import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.service.StatisticsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics/heart-rate")
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public HeartRateSummaryResponse heartRateSummary(
            Authentication authentication,
            @RequestParam(required = false) String date) {
        return service.heartRateSummary(authentication.getName(), date);
    }
}
