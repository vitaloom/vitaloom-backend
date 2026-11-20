package com.vitaloom.backend.profile.controller;

import com.vitaloom.backend.auth.exception.UserNotFoundException;
import com.vitaloom.backend.profile.dto.ProfileRequests;
import com.vitaloom.backend.profile.dto.ProfileResponses;
import com.vitaloom.backend.profile.service.HealthProfileService;
import com.vitaloom.backend.shared.web.ProblemDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class HealthProfileController {
    private final HealthProfileService profiles;

    public HealthProfileController(HealthProfileService profiles) { this.profiles = profiles; }

    @GetMapping("/health-profile")
    public ProfileResponses.Result read(Authentication authentication, @RequestParam(required = false) String attemptId) {
        return profiles.read(authentication.getName(), attemptId);
    }

    @PostMapping("/health-profile")
    public ResponseEntity<?> create(Authentication authentication, @RequestBody ProfileRequests.Create request) {
        ProfileResponses.Result result = profiles.create(authentication.getName(), request);
        if ("validation-error".equals(result.kind())) return problem(HttpStatus.UNPROCESSABLE_ENTITY, result);
        if ("conflict".equals(result.kind())) return problem(HttpStatus.CONFLICT, result);
        if ("success".equals(result.kind())) return ResponseEntity.status(HttpStatus.CREATED).body(result);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/health-profile")
    public ResponseEntity<?> update(Authentication authentication, @RequestBody ProfileRequests.Update request) {
        ProfileResponses.Result result = profiles.update(authentication.getName(), request);
        if ("validation-error".equals(result.kind())) return problem(HttpStatus.UNPROCESSABLE_ENTITY, result);
        if ("conflict".equals(result.kind())) return problem(HttpStatus.PRECONDITION_FAILED, result);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/profile-invitation")
    public ResponseEntity<?> decide(Authentication authentication, @RequestBody ProfileRequests.Invitation request) {
        ProfileResponses.Result result = profiles.decide(authentication.getName(), request);
        if ("validation-error".equals(result.kind())) return problem(HttpStatus.UNPROCESSABLE_ENTITY, result);
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> missingUser() {
        return ProblemDetails.response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ProfileResponses.Result result) {
        ProblemDetail problem = ProblemDetails.body(status, result.code(), result.detail());
        problem.setProperty("kind", result.kind());
        if (result.snapshot() != null) problem.setProperty("snapshot", result.snapshot());
        if (result.errors() != null) problem.setProperty("errors", result.errors());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
