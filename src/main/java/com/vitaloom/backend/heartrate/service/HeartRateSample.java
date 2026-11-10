package com.vitaloom.backend.heartrate.service;

import java.time.Instant;

/**
 * Read-only raw Heart Rate observation exposed to other backend features.
 */
public record HeartRateSample(int heartRateBpm, Instant occurredAt) {
}
