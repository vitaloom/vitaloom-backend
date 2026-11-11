package com.vitaloom.backend.heartrate.controller.exceptionhandler;

import com.vitaloom.backend.heartrate.controller.HeartRateController;
import com.vitaloom.backend.heartrate.exception.InvalidHeartRateRequestException;
import com.vitaloom.backend.shared.web.ProblemDetailExceptionHandler;
import com.vitaloom.backend.shared.web.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = HeartRateController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HeartRateExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "INVALID_REQUEST";
    }

    @Override
    protected String frameworkDetail(Exception exception, HttpStatusCode status) {
        if (exception instanceof HttpMessageNotReadableException) return "Request body is missing or malformed.";
        return super.frameworkDetail(exception, status);
    }

    @ExceptionHandler(InvalidHeartRateRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(InvalidHeartRateRequestException exception) {
        return ProblemDetails.response(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage());
    }
}
