package com.vitaloom.backend.statistics.controller;

import com.vitaloom.backend.statistics.dto.response.HeartRateTimeSeriesResponse;
import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import com.vitaloom.backend.statistics.service.StatisticsService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/statistics/heart-rate")
@RequiredArgsConstructor
public class HeartRateTimeSeriesController {
    private final StatisticsService service;

    @GetMapping("/daily")
    public HeartRateTimeSeriesResponse dailyHeartRateTimeSeries(
            Authentication authentication,
            @RequestParam(required = false) String date) {
        return service.dailyHeartRateTimeSeries(authentication.getName(), parseOptionalDate(date, "INVALID_REPORT_DATE"));
    }

    @GetMapping("/weekly")
    public HeartRateTimeSeriesResponse weeklyHeartRateTimeSeries(
            Authentication authentication,
            @RequestParam(required = false) String weekStartDate) {
        return service.weeklyHeartRateTimeSeries(
                authentication.getName(), parseOptionalDate(weekStartDate, "INVALID_WEEK_START_DATE"));
    }

    @GetMapping("/custom")
    public HeartRateTimeSeriesResponse customHeartRateTimeSeries(
            Authentication authentication,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return service.customHeartRateTimeSeries(
                authentication.getName(),
                parseOptionalDate(startDate, "INVALID_DATE_RANGE"),
                parseOptionalDate(endDate, "INVALID_DATE_RANGE"));
    }

    private static LocalDate parseOptionalDate(String value, String errorCode) {
        if (value == null) return null;
        if (value.length() != 10) throw invalidDate(errorCode);
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) throw invalidDate(errorCode);
            return date;
        } catch (DateTimeParseException exception) {
            throw invalidDate(errorCode);
        }
    }

    private static InvalidStatisticsRequestException invalidDate(String errorCode) {
        String message = switch (errorCode) {
            case "INVALID_REPORT_DATE" -> "date must use YYYY-MM-DD format.";
            case "INVALID_WEEK_START_DATE" -> "weekStartDate must use YYYY-MM-DD format.";
            default -> "startDate and endDate must use YYYY-MM-DD format.";
        };
        return new InvalidStatisticsRequestException(errorCode, message);
    }
}
