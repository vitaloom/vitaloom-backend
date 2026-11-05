package com.vitaloom.backend.auth.exception;

import java.util.Map;
import com.vitaloom.backend.auth.service.TokenIssuanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ResponseEntity<?> duplicateEmail() { return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists."); }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<?> invalidCredentials() { return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng."); }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<?> invalidRefresh() { return error(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "The refresh token is invalid or expired."); }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<?> missingUser() { return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalidRequest() { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request payload is invalid."); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadableRequest() { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request payload is invalid."); }

    @ExceptionHandler(TokenIssuanceException.class)
    ResponseEntity<?> tokenFailure() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Authentication could not be completed."); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpectedFailure() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed."); }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
