package com.vitaloom.backend.sleep.contract.dto;

import java.time.Instant;
import java.time.LocalDate;

public record MainSleepInsight(String id, LocalDate day, int minutes, String feeling, Instant confirmedAt) {}
