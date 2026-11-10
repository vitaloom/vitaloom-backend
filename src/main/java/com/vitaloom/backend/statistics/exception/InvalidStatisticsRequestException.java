package com.vitaloom.backend.statistics.exception;

public class InvalidStatisticsRequestException extends RuntimeException {
    private final String code;

    public InvalidStatisticsRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
