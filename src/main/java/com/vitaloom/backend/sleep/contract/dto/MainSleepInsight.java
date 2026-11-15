package com.vitaloom.backend.sleep.contract.dto;

import java.time.Instant;
import java.time.LocalDate;

public record MainSleepInsight(String id, LocalDate day, String feeling, Instant confirmedAt) {}
