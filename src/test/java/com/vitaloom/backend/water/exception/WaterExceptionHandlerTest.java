package com.vitaloom.backend.water.exception;

import com.vitaloom.backend.water.controller.exceptionhandler.WaterExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

class WaterExceptionHandlerTest {
    private final WaterExceptionHandler handler = new WaterExceptionHandler();

    @Test void futureTimestampUsesProblemDetailsAndKeepsSpecificMessage() {
        var response = handler.invalidRequest(new WaterValidationException("loggedAt cannot be in the future."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("loggedAt cannot be in the future.");
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test void duplicateWaterLogUsesConflictProblemDetails() {
        var response = handler.duplicateLog(new WaterLogDuplicateException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getStatus()).isEqualTo(409);
        assertThat(response.getBody().getProperties()).containsEntry("code", "WATER_LOG_DUPLICATE");
    }
}
