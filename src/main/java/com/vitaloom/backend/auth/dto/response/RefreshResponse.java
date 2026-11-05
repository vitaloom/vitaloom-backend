package com.vitaloom.backend.auth.dto.response;

public record RefreshResponse(String accessToken, long expiresInSeconds) {}
