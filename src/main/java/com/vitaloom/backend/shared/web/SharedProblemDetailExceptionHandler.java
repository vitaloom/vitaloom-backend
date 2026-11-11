package com.vitaloom.backend.shared.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class SharedProblemDetailExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "INVALID_REQUEST";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpectedFailure(Exception exception) {
        return ProblemDetails.response(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "The request could not be completed.");
    }
}
