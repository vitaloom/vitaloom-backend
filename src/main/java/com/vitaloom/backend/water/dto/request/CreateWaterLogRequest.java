package com.vitaloom.backend.water.dto.request;

import com.vitaloom.backend.water.exception.WaterValidationException;
import tools.jackson.databind.JsonNode;

public record CreateWaterLogRequest(JsonNode amountMl, String loggedAt) {
    public int validatedAmountMl() {
        if (amountMl == null || !amountMl.isInt()) throw new WaterValidationException();
        return amountMl.intValue();
    }
}
