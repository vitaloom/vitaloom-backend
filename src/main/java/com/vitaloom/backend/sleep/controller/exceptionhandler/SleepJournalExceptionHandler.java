package com.vitaloom.backend.sleep.controller.exceptionhandler;

import com.vitaloom.backend.shared.web.ProblemDetailExceptionHandler;
import com.vitaloom.backend.shared.web.ProblemDetails;
import com.vitaloom.backend.sleep.controller.SleepJournalController;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes = SleepJournalController.class)
public class SleepJournalExceptionHandler extends ProblemDetailExceptionHandler {
    @Override
    protected String defaultClientErrorCode() {
        return "VALIDATION_ERROR";
    }

    @ExceptionHandler(SleepFailure.class)
    public ResponseEntity<ProblemDetail> sleepFailure(SleepFailure failure) {
        var status = HttpStatusCode.valueOf(failure.status());
        var body = ProblemDetails.body(status, failure.code(), failure.getMessage());
        if (failure.field() != null) body.setProperty("field", failure.field());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
