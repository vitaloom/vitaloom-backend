package com.vitaloom.backend.auth.controller;

import com.vitaloom.backend.auth.dto.request.LoginRequest;
import com.vitaloom.backend.auth.dto.request.RefreshRequest;
import com.vitaloom.backend.auth.dto.request.RegisterRequest;
import com.vitaloom.backend.auth.dto.response.AuthResponse;
import com.vitaloom.backend.auth.dto.response.RefreshResponse;
import com.vitaloom.backend.auth.dto.response.RegistrationFallbackResponse;
import com.vitaloom.backend.auth.dto.response.UserResponse;
import com.vitaloom.backend.auth.service.AuthService;
import com.vitaloom.backend.auth.service.JwtTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.RegistrationResult result = authService.register(request.email(), request.password());
        if (result.loginRequired()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new RegistrationFallbackResponse(result.user(), "LOGIN_REQUIRED"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.authResponse());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        AuthService.RefreshResult result = authService.refresh(request.refreshToken());
        return new RefreshResponse(result.accessToken(), JwtTokenService.ACCESS_SECONDS);
    }

    @GetMapping("/me")
    public UserResponse currentUser(Authentication authentication) {
        return authService.currentUser(authentication.getName());
    }
}
