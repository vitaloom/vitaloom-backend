package com.vitaloom.backend.sleep.exception;

public class SleepFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;

    public SleepFailure(int status, String code, String message) {
        this(status, code, message, null);
    }

    public SleepFailure(int status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
}
