package com.vitaloom.backend.water.controller;

import com.vitaloom.backend.water.dto.request.CreateWaterLogRequest;
import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.dto.response.WeeklyWaterProgressResponse;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.water.service.WaterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/water")
public class WaterController {
    private final WaterService water;

    public WaterController(WaterService water) { this.water = water; }

    @GetMapping("/daily-summary")
    public DailyWaterSummaryResponse dailySummary(
            @RequestParam String date, @RequestParam String timeZone, Authentication authentication) {
        return water.dailySummary(authentication.getName(), date, timeZone);
    }

    @GetMapping("/weekly-progress")
    public WeeklyWaterProgressResponse weeklyProgress(
            @RequestParam String weekStart, @RequestParam String timeZone, Authentication authentication) {
        return water.weeklyProgress(authentication.getName(), weekStart, timeZone);
    }

    @PostMapping("/logs")
    public ResponseEntity<WaterLogResponse> create(
            @RequestBody CreateWaterLogRequest request, Authentication authentication) {
        if (request == null) throw new WaterValidationException();
        WaterLogResponse created = water.create(
                authentication.getName(), request.validatedAmountMl(), request.validatedLoggedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
