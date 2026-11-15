package com.vitaloom.backend.sleep.contract.dto;

import java.time.LocalDate;

public record MainSleepDay(String sourceId, LocalDate day, int minutes) {}
