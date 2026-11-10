package com.vitaloom.backend.water.dto.response;

import java.time.Instant;
import com.vitaloom.backend.water.model.WaterLog;

public record WaterLogResponse(String id, int amountMl, Instant loggedAt) {
    public static WaterLogResponse from(WaterLog log) {
        return new WaterLogResponse(log.getId(), log.getAmountMl(), log.getLoggedAt());
    }
}
