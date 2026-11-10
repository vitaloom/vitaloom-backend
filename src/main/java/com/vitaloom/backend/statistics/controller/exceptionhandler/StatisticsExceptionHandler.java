package com.vitaloom.backend.statistics.controller.exceptionhandler;

import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class StatisticsExceptionHandler {
    @ExceptionHandler(InvalidStatisticsRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidRequest(InvalidStatisticsRequestException exception) {
        return Map.of("error", Map.of("code", exception.getCode(), "message", exception.getMessage()));
    }
}
