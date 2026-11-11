package com.vitaloom.backend.steps.exception;

public class InvalidStepsRequestException extends RuntimeException {
    private final String code;
    public InvalidStepsRequestException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
