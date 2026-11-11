package com.vitaloom.backend.heartrate.contract.dto;

import java.time.Instant;

public record HeartRateSample(int heartRateBpm, Instant occurredAt) {
}
