package com.vitaloom.backend.water.dto.request;

import com.vitaloom.backend.water.exception.WaterValidationException;
import tools.jackson.databind.JsonNode;

public record CreateWaterLogRequest(JsonNode amountMl, JsonNode loggedAt) {
    public int validatedAmountMl() {
        if (amountMl == null || !amountMl.isInt()) throw new WaterValidationException();
        return amountMl.intValue();
    }

    public String validatedLoggedAt() {
        if (loggedAt == null) return null;
        if (!loggedAt.isString()) throw new WaterValidationException();
        return loggedAt.stringValue();
    }
}
