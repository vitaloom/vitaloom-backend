package com.vitaloom.backend.heartrate.controller;

import com.vitaloom.backend.heartrate.dto.request.HeartRateBatchRequest;
import com.vitaloom.backend.heartrate.dto.response.HeartRateBatchResponse;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/heart-rate")
@RequiredArgsConstructor
public class HeartRateController {
    private final HeartRateService service;

    @PostMapping("/batch")
    public ResponseEntity<HeartRateBatchResponse> ingest(Authentication authentication,
                                                           @RequestBody HeartRateBatchRequest request) {
        return ResponseEntity.ok(service.ingest(authentication.getName(), request));
    }
}
