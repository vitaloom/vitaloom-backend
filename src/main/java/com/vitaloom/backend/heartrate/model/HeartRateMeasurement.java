package com.vitaloom.backend.heartrate.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("heart_rate_measurements")
@CompoundIndexes({
        @CompoundIndex(name = "uk_hr_account_device_event", def = "{'account_id': 1, 'device_id': 1, 'event_id': 1}", unique = true),
        @CompoundIndex(name = "ix_hr_account_occurred_at", def = "{'account_id': 1, 'occurred_at': 1}")
})
public class HeartRateMeasurement extends BaseDocument {
    @Field("account_id") private String accountId;
    @Field("device_id") private String deviceId;
    @Field("event_id") private String eventId;
    @Field("heart_rate_bpm") private int heartRateBpm;
    @Field("occurred_at") private Instant occurredAt;
    @Field("received_at") private Instant receivedAt;

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
