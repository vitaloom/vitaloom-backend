package com.vitaloom.backend.sleep.goal;

public record SleepGoalResponse(Integer minutes, long revision, String effectiveDay, boolean verified) {}
