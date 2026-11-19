package com.vitaloom.backend.sleep.contract.dto;

import java.util.List;

public record MainSleepInsightWindow(boolean verified, List<MainSleepInsight> records) {
    public MainSleepInsightWindow { records = List.copyOf(records); }
}
