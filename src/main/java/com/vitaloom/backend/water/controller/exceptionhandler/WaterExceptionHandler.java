package com.vitaloom.backend.water.controller.exceptionhandler;

import com.vitaloom.backend.water.controller.WaterController;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.shared.web.ProblemDetailExceptionHandler;
import com.vitaloom.backend.shared.web.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WaterController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WaterExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "VALIDATION_ERROR";
    }

    @Override
    protected String frameworkDetail(Exception exception, HttpStatusCode status) {
        if (status.value() == 400) return "The water request is invalid.";
        return super.frameworkDetail(exception, status);
    }

    @ExceptionHandler(WaterValidationException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(WaterValidationException exception) {
        return ProblemDetails.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage());
    }
}
