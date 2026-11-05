package com.vitaloom.backend.auth.dto.response;

public record AuthResponse(UserResponse user, String accessToken, String refreshToken, long expiresInSeconds) {}
