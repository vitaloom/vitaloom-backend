package com.vitaloom.backend.water.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitaloom.backend.water.dto.request.CreateWaterLogRequest;
import com.vitaloom.backend.water.exception.WaterValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CreateWaterLogRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptsOnlyIntegerJsonAmounts() {
        assertThat(new CreateWaterLogRequest(json.readTree("250"), json.readTree("\"2026-11-05T23:30:00Z\""))
                .validatedAmountMl()).isEqualTo(250);
        assertThatThrownBy(() -> new CreateWaterLogRequest(json.readTree("250.5"), json.readTree("\"2026-11-05T23:30:00Z\""))
                .validatedAmountMl()).isInstanceOf(WaterValidationException.class);
        assertThatThrownBy(() -> new CreateWaterLogRequest(json.readTree("\"250\""), json.readTree("\"2026-11-05T23:30:00Z\""))
                .validatedAmountMl()).isInstanceOf(WaterValidationException.class);
    }

    @Test void acceptsOmittedOrStringTimestampAndRejectsNullOrOtherTypes() {
        var omitted = json.readValue("{\"amountMl\":250}", CreateWaterLogRequest.class);
        assertThat(omitted.validatedLoggedAt()).isNull();

        var supplied = json.readValue("{\"amountMl\":250,\"loggedAt\":\"2026-11-05T23:30:00Z\"}",
                CreateWaterLogRequest.class);
        assertThat(supplied.validatedLoggedAt()).isEqualTo("2026-11-05T23:30:00Z");

        var explicitNull = json.readValue("{\"amountMl\":250,\"loggedAt\":null}", CreateWaterLogRequest.class);
        assertThatThrownBy(explicitNull::validatedLoggedAt).isInstanceOf(WaterValidationException.class);

        var wrongType = json.readValue("{\"amountMl\":250,\"loggedAt\":250}", CreateWaterLogRequest.class);
        assertThatThrownBy(wrongType::validatedLoggedAt).isInstanceOf(WaterValidationException.class);
    }
}
