package com.vitaloom.backend.heartrate.exception;

import com.vitaloom.backend.heartrate.service.InvalidHeartRateRequestException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class HeartRateExceptionHandler {
    @ExceptionHandler(InvalidHeartRateRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidRequest(InvalidHeartRateRequestException exception) {
        return error(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> unreadableBody(HttpMessageNotReadableException exception) {
        return error("INVALID_REQUEST", "Request body is missing or malformed.");
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
