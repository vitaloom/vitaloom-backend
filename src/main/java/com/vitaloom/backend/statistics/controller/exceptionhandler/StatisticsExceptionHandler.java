package com.vitaloom.backend.statistics.controller.exceptionhandler;

import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import com.vitaloom.backend.shared.web.ProblemDetailExceptionHandler;
import com.vitaloom.backend.shared.web.ProblemDetails;
import com.vitaloom.backend.statistics.controller.HeartRateTimeSeriesController;
import com.vitaloom.backend.statistics.controller.StatisticsController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {StatisticsController.class, HeartRateTimeSeriesController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StatisticsExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "INVALID_REQUEST";
    }

    @ExceptionHandler(InvalidStatisticsRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(InvalidStatisticsRequestException exception) {
        return ProblemDetails.response(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage());
    }
}
