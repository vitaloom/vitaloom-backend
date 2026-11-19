package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.dto.response.SleepInsightDetailResponse;
import com.vitaloom.backend.sleep.dto.response.SleepInsightsResponse;
import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepRecentReportService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SleepReportInsightController {
    private static final Set<String> KNOWN_ZONES = ZoneId.getAvailableZoneIds();
    private final SleepRecentReportService report;
    private final SleepInsightsStatisticsService insights;

    @GetMapping("/api/sleep/recent")
    public ResponseEntity<SleepRecentReportResponse> recent(
            Authentication authentication, HttpServletRequest request) {
        String catalog = parameter(request, "catalog");
        boolean full = "full".equals(catalog);
        validateParameters(request, full ? Set.of("catalog", "timeZone") : Set.of());
        ZoneId zone = full ? selectedZone(request) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(report.recent(authentication.getName(), full, zone));
    }

    @GetMapping("/api/sleep/insights")
    public ResponseEntity<SleepInsightsResponse> insights(Authentication authentication, HttpServletRequest request) {
        String catalog = parameter(request, "catalog");
        validateParameters(request, "full".equals(catalog) ? Set.of("catalog", "timeZone") : Set.of("catalog"));
        String accountId = authentication.getName();
        var result = switch (catalog) {
            case null -> insights.insights(accountId);
            case "goal" -> insights.goalInsights(accountId);
            case "trend" -> insights.trendInsights(accountId);
            case "full" -> insights.fullInsights(accountId, selectedZone(request));
            default -> throw invalidRequest("Only catalog=goal, catalog=trend or catalog=full is supported.");
        };
        var period = result.period();
        var items = result.items().stream().map(item -> new SleepInsightsResponse.Item(
                item.code(), item.category(), item.status(), item.title(), item.highlight(), item.locked(),
                item.summary(), item.sample(), item.evidence(), item.sourceRefs(), item.limitations(),
                item.policyVersion(), item.evidenceVersion())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SleepInsightsResponse(
                new SleepInsightsResponse.Period(period.start(), period.end(), period.timeZone()), items,
                result.timeZones()));
    }

    @GetMapping("/api/sleep/insights/{code}")
    public ResponseEntity<SleepInsightDetailResponse> detail(@PathVariable String code,
            Authentication authentication, HttpServletRequest request) {
        validateParameters(request, "A04".equals(code) ? Set.of("timeZone") : Set.of());
        ZoneId zone = selectedZone(request);
        com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Detail result;
        try {
            result = zone == null ? insights.detail(authentication.getName(), code)
                    : insights.detail(authentication.getName(), code, zone);
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

    private static void validateParameters(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())) {
            throw invalidRequest("The request contains unsupported query parameters.");
        }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1 || values[0].isBlank()) {
            throw invalidRequest("A single non-empty " + name + " query value is required.");
        }
        return values[0];
    }

    private static ZoneId selectedZone(HttpServletRequest request) {
        String id = parameter(request, "timeZone");
        if (id == null) return null;
        if (!KNOWN_ZONES.contains(id)) throw invalidRequest("Select a valid IANA timeZone.");
        return ZoneId.of(id);
    }

    private static SleepFailure invalidRequest(String message) {
        return new SleepFailure(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", message);
    }
}
