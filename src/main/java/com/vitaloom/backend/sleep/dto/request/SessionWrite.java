package com.vitaloom.backend.sleep.dto.request;

import tools.jackson.databind.JsonNode;

public record SessionWrite(
        String sleepType,
        String method,
        String feeling,
        String timeZone,
        CivilTime start,
        CivilTime end,
        String endDay,
        JsonNode minutes) {
    public record CivilTime(String day, String hour, String minute, JsonNode offsetMinutes) {}
}
