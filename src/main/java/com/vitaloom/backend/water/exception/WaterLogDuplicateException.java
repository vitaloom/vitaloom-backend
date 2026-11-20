package com.vitaloom.backend.water.exception;

public class WaterLogDuplicateException extends RuntimeException {
    public WaterLogDuplicateException() {
        super("A water log already exists at this timestamp.");
    }
}
