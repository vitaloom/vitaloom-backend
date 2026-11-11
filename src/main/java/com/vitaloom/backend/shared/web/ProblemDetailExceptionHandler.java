package com.vitaloom.backend.shared.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

public abstract class ProblemDetailExceptionHandler extends ResponseEntityExceptionHandler {
    protected abstract String defaultClientErrorCode();

    protected String frameworkDetail(Exception exception, HttpStatusCode status) {
        if (status.value() == 404) return "The requested resource was not found.";
        if (status.value() == 405) return "The request method is not supported for this resource.";
        if (status.value() == 415) return "The request content type is not supported.";
        if (status.is5xxServerError()) return "The request could not be completed.";
        return "The request is invalid.";
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : defaultClientErrorCode();
        };
        ProblemDetail problem = ProblemDetails.body(status, code, frameworkDetail(exception, status));
        HttpHeaders problemHeaders = new HttpHeaders();
        problemHeaders.putAll(headers);
        problemHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(exception, problem, problemHeaders, status, request);
    }
}
