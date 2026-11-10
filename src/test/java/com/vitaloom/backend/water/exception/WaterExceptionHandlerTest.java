package com.vitaloom.backend.water.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WaterExceptionHandlerTest {
    private final WaterExceptionHandler handler = new WaterExceptionHandler();

    @Test void futureTimestampKeepsValidationEnvelopeAndSpecificMessage() {
        var response = handler.invalidRequest(new WaterValidationException("loggedAt cannot be in the future."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", java.util.Map.of(
                "code", "VALIDATION_ERROR", "message", "loggedAt cannot be in the future.")));
    }
}
