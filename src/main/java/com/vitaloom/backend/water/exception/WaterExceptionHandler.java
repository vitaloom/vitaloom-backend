package com.vitaloom.backend.water.exception;

import java.util.Map;
import com.vitaloom.backend.water.controller.WaterController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WaterController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WaterExceptionHandler {
    @ExceptionHandler(WaterValidationException.class)
    public ResponseEntity<?> invalidRequest() {
        return validationError();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> malformedRequest() {
        return validationError();
    }

    private ResponseEntity<?> validationError() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", Map.of(
                "code", "VALIDATION_ERROR", "message", "The water request is invalid.")));
    }
}
