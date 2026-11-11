package com.vitaloom.backend.auth.controller.exceptionhandler;

import com.vitaloom.backend.auth.controller.AuthController;
import com.vitaloom.backend.auth.exception.EmailAlreadyRegisteredException;
import com.vitaloom.backend.auth.exception.InvalidCredentialsException;
import com.vitaloom.backend.auth.exception.InvalidRefreshTokenException;
import com.vitaloom.backend.auth.exception.TokenIssuanceException;
import com.vitaloom.backend.auth.exception.UserNotFoundException;
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

@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "VALIDATION_ERROR";
    }

    @Override
    protected String frameworkDetail(Exception exception, HttpStatusCode status) {
        if (status.value() == 400) return "The request payload is invalid.";
        return super.frameworkDetail(exception, status);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> duplicateEmail(EmailAlreadyRegisteredException exception) {
        return ProblemDetails.response(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "An account with this email already exists.");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException exception) {
        return ProblemDetails.response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Email hoặc mật khẩu không đúng.");
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> invalidRefresh(InvalidRefreshTokenException exception) {
        return ProblemDetails.response(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "The refresh token is invalid or expired.");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> missingUser(UserNotFoundException exception) {
        return ProblemDetails.response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    @ExceptionHandler(TokenIssuanceException.class)
    public ResponseEntity<ProblemDetail> tokenFailure(TokenIssuanceException exception) {
        return ProblemDetails.response(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Authentication could not be completed.");
    }
}
