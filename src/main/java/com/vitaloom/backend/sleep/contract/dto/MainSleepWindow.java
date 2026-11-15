package com.vitaloom.backend.sleep.contract.dto;

import java.util.List;

public record MainSleepWindow(boolean verified, List<MainSleepDay> records) {
    public MainSleepWindow {
        records = List.copyOf(records);
    }
}
