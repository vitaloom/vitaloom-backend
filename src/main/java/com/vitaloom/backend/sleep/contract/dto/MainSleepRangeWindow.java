package com.vitaloom.backend.sleep.contract.dto;

import java.util.List;

public record MainSleepRangeWindow(boolean verified, List<MainSleepRangeDay> records) {
    public MainSleepRangeWindow {
        records = List.copyOf(records);
    }
}
