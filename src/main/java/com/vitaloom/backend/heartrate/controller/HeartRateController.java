package com.vitaloom.backend.heartrate.controller;

import com.vitaloom.backend.heartrate.dto.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.HeartRateBatchResponse;
import com.vitaloom.backend.heartrate.dto.HeartRateSummaryResponse;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/heart-rate")
public class HeartRateController {
    private final HeartRateService service;
    public HeartRateController(HeartRateService service) { this.service = service; }

    @PostMapping("/batch")
    public ResponseEntity<HeartRateBatchResponse> ingest(Authentication authentication,
                                                           @RequestBody HeartRateBatchRequest request) {
        return ResponseEntity.ok(service.ingest(authentication.getName(), request));
    }

    @GetMapping("/summary")
    public HeartRateSummaryResponse summary(Authentication authentication,
                                            @RequestParam(required = false) String date) {
        return service.summary(authentication.getName(), date);
    }
}
