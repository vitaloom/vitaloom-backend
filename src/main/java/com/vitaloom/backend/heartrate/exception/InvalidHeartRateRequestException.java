package com.vitaloom.backend.heartrate.exception;

public class InvalidHeartRateRequestException extends RuntimeException {
    private final String code;
    public InvalidHeartRateRequestException(String code, String message) { super(message); this.code = code; }
    public String getCode() { return code; }
}
