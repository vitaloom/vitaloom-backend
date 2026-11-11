package com.vitaloom.backend.shared.web;

import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

public final class ProblemDetails {
    private ProblemDetails() {}

    public static ProblemDetail body(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(status, code, detail));
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper,
                             HttpStatusCode status, String code, String detail, String instance) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = body(status, code, detail);
        problem.setInstance(URI.create(instance));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
