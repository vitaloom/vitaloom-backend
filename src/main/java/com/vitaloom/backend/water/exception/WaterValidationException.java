package com.vitaloom.backend.water.exception;

public class WaterValidationException extends RuntimeException {
    public WaterValidationException() { super("The water request is invalid."); }

    public WaterValidationException(String message) { super(message); }
}
