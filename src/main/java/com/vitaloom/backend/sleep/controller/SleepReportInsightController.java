package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepRecentReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SleepReportInsightController {
    private final SleepRecentReportService report;

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
}
