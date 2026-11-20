package com.vitaloom.backend.auth.dto.response;

public record UserResponse(String id, String email, String profileInvitation) {
    public UserResponse(String id, String email) { this(id, email, "pending"); }
}
