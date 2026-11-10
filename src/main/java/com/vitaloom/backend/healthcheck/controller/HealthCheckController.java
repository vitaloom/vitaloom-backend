package com.vitaloom.backend.healthcheck.controller;

import com.vitaloom.backend.healthcheck.dto.response.HealthCheckResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/api/health")
    public HealthCheckResponse health() {
        return new HealthCheckResponse("UP");
    }
}
