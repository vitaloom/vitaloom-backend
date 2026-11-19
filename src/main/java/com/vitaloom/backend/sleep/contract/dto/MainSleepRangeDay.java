package com.vitaloom.backend.sleep.contract.dto;

import java.time.Instant;
import java.time.LocalDate;

public record MainSleepRangeDay(
        String sourceId,
        LocalDate day,
        int minutes,
        String method,
        String timeZone,
        Instant startAt,
        Instant endAt) {}
