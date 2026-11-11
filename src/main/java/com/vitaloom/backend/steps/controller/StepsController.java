package com.vitaloom.backend.steps.controller;

import com.vitaloom.backend.steps.dto.StepsBatchRequest;
import com.vitaloom.backend.steps.dto.StepsBatchResponse;
import com.vitaloom.backend.steps.service.StepsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/steps")
@RequiredArgsConstructor
public class StepsController {
    private final StepsService service;

    @PostMapping("/batch")
    public ResponseEntity<StepsBatchResponse> ingest(Authentication authentication,
                                                      @RequestBody StepsBatchRequest request) {
        return ResponseEntity.ok(service.ingest(authentication.getName(), request));
    }
}
