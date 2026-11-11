package com.vitaloom.backend.heartrate.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("heart_rate_measurements")
@CompoundIndexes({
        @CompoundIndex(name = "uk_hr_account_device_event", def = "{'accountId': 1, 'deviceId': 1, 'eventId': 1}", unique = true),
        @CompoundIndex(name = "ix_hr_account_occurred_at", def = "{'accountId': 1, 'occurredAt': 1}")
})
public class HeartRateMeasurement extends BaseDocument {
    private String accountId;
    private String deviceId;
    private String eventId;
    private int heartRateBpm;
    private Instant occurredAt;
    private Instant receivedAt;

    public HeartRateMeasurement() {}
    public HeartRateMeasurement(String accountId, String deviceId, String eventId, int heartRateBpm,
                                Instant occurredAt, Instant receivedAt) {
        this.accountId = accountId;
        this.deviceId = deviceId;
        this.eventId = eventId;
        this.heartRateBpm = heartRateBpm;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }
    public String getAccountId() { return accountId; }
    public String getDeviceId() { return deviceId; }
    public String getEventId() { return eventId; }
    public int getHeartRateBpm() { return heartRateBpm; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
