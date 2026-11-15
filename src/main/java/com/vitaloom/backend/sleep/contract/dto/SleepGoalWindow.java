package com.vitaloom.backend.sleep.contract.dto;

import java.time.LocalDate;
import java.util.Map;

public record SleepGoalWindow(boolean verified, Map<LocalDate, Integer> goals) {
    public SleepGoalWindow {
        goals = Map.copyOf(goals);
    }
}
