package com.vitaloom.backend.steps.goal.controller;

import com.vitaloom.backend.steps.goal.dto.request.SetStepGoalRequest;
import com.vitaloom.backend.steps.goal.dto.response.StepGoalResponse;
import com.vitaloom.backend.steps.goal.service.StepGoalService;
import com.vitaloom.backend.steps.exception.InvalidStepsRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/steps/goal")
public class StepGoalController {
    private final StepGoalService service;

    public StepGoalController(StepGoalService service) { this.service = service; }

    @PutMapping
    public StepGoalResponse setGoal(Authentication authentication, @RequestBody SetStepGoalRequest request) {
        if (request == null) throw invalidGoal();
        return service.setGoal(authentication.getName(), request.validatedTargetSteps());
    }

    @DeleteMapping
    public ResponseEntity<Void> removeGoal(Authentication authentication) {
        service.removeGoal(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    private static InvalidStepsRequestException invalidGoal() {
        return new InvalidStepsRequestException("INVALID_STEP_GOAL", "targetSteps must be a positive safe integer.");
    }
}
