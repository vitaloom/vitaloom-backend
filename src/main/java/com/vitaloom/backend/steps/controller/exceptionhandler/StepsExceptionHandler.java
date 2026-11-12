package com.vitaloom.backend.steps.controller.exceptionhandler;

import com.vitaloom.backend.shared.web.ProblemDetailExceptionHandler;
import com.vitaloom.backend.shared.web.ProblemDetails;
import com.vitaloom.backend.steps.controller.StepsController;
import com.vitaloom.backend.steps.goal.controller.StepGoalController;
import com.vitaloom.backend.steps.exception.InvalidStepsRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice(assignableTypes = {StepsController.class, StepGoalController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StepsExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "INVALID_REQUEST";
    }

    @Override
    protected String frameworkDetail(Exception exception, HttpStatusCode status) {
        if (exception instanceof HttpMessageNotReadableException) return "Request body is missing or malformed.";
        return super.frameworkDetail(exception, status);
    }

    @ExceptionHandler(InvalidStepsRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(InvalidStepsRequestException exception) {
        return ProblemDetails.response(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage());
    }
}
