package com.vitaloom.backend.profile.dto;

import java.util.Map;

public final class ProfileRequests {
    private ProfileRequests() {}
    public record Create(Map<String, Object> fields, String attemptId) {}
    public record Update(Map<String, Object> patch, String expectedRevision, String attemptId) {}
    public record Invitation(String decision) {}
}
