package com.vitaloom.backend.water.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("water_logs")
@CompoundIndex(name = "user_logged_at_idx", def = "{'userId': 1, 'loggedAt': -1}")
public class WaterLog extends BaseDocument {
    private String userId;
    private int amountMl;
    private Instant loggedAt;

    public WaterLog() {}
    public WaterLog(String userId, int amountMl, Instant loggedAt) {
        this.userId = userId;
        this.amountMl = amountMl;
        this.loggedAt = loggedAt;
    }
    public String getUserId() { return userId; }
    public int getAmountMl() { return amountMl; }
    public Instant getLoggedAt() { return loggedAt; }
}
