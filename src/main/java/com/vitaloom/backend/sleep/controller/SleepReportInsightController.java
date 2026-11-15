package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepRecentReportService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import com.vitaloom.backend.sleep.dto.response.SleepInsightsResponse;
import com.vitaloom.backend.sleep.dto.response.SleepInsightDetailResponse;

@RestController
@RequiredArgsConstructor
public class SleepReportInsightController {
    private final SleepRecentReportService report;
    private final SleepInsightsStatisticsService insights;

    @GetMapping("/api/sleep/recent")
    public ResponseEntity<SleepRecentReportResponse> recent(
            Authentication authentication, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new SleepFailure(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST",
                    "The recent Sleep report does not accept query parameters.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(report.recent(authentication.getName()));
    }

    @GetMapping("/api/sleep/insights")
    public ResponseEntity<SleepInsightsResponse> insights(Authentication authentication, HttpServletRequest request) {
        rejectParameters(request, "Sleep insights do not accept query parameters.");
        var result = insights.insights(authentication.getName());
        var period = result.period();
        var items = result.items().stream().map(item -> new SleepInsightsResponse.Item(
                item.code(), item.category(), item.status(), item.title(), item.highlight(), item.locked(),
                item.summary(), item.sample(), item.evidence(), item.sourceRefs(), item.limitations(),
                item.policyVersion(), item.evidenceVersion())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SleepInsightsResponse(
                new SleepInsightsResponse.Period(period.start(), period.end(), period.timeZone()), items));
    }

    @GetMapping("/api/sleep/insights/{code}")
    public ResponseEntity<SleepInsightDetailResponse> detail(@PathVariable String code,
            Authentication authentication, HttpServletRequest request) {
        rejectParameters(request, "Sleep insight detail does not accept query parameters.");
        com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Detail result;
        try {
            result = insights.detail(authentication.getName(), code);
        } catch (IllegalArgumentException exception) {
            throw new SleepFailure(HttpStatus.NOT_FOUND.value(), "INSIGHT_NOT_FOUND", "Sleep insight was not found.");
        }
        var period = result.period();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SleepInsightDetailResponse(
                result.code(), result.category(), result.status(),
                new SleepInsightDetailResponse.Period(period.start(), period.end(), period.timeZone()), result.title(),
                result.summary(), result.sample(), result.evidence(),
                result.sourceRefs() == null ? List.of() : result.sourceRefs(),
                result.limitations() == null ? List.of() : result.limitations(),
                result.policyVersion() == null ? "sleep-recent-mvp-2026-11-14" : result.policyVersion(),
                result.evidenceVersion() == null ? "" : result.evidenceVersion()));
    }

    private static void rejectParameters(HttpServletRequest request, String message) {
        if (!request.getParameterMap().isEmpty()) {
            throw new SleepFailure(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", message);
        }
    }
}
