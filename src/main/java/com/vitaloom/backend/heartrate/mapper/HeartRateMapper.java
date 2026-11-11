package com.vitaloom.backend.heartrate.mapper;

import com.vitaloom.backend.heartrate.model.HeartRateMeasurement;
import com.vitaloom.backend.heartrate.contract.dto.HeartRateSample;

public final class HeartRateMapper {
    private HeartRateMapper() {}

    public static HeartRateSample toSample(HeartRateMeasurement measurement) {
        return new HeartRateSample(measurement.getHeartRateBpm(), measurement.getOccurredAt());
    }
}
