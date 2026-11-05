package com.vitaloom.backend.auth.dto.response;

public record RegistrationFallbackResponse(UserResponse user, String authStatus) {}
