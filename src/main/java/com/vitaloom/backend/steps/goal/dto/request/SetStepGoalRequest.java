package com.vitaloom.backend.steps.goal.dto.request;

import com.vitaloom.backend.steps.exception.InvalidStepsRequestException;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

public record SetStepGoalRequest(JsonNode targetSteps) {
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    public long validatedTargetSteps() {
        if (targetSteps == null || !targetSteps.isIntegralNumber()) throw invalidGoal();
        BigInteger value = targetSteps.bigIntegerValue();
        if (value.signum() <= 0 || value.compareTo(MAX_SAFE_INTEGER) > 0) throw invalidGoal();
        return value.longValueExact();
    }

    private static InvalidStepsRequestException invalidGoal() {
        return new InvalidStepsRequestException("INVALID_STEP_GOAL",
                "targetSteps must be a positive safe integer.");
    }
}
