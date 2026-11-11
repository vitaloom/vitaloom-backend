package com.vitaloom.backend.steps.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("step_measurements")
@CompoundIndexes({
        @CompoundIndex(name = "uk_steps_account_device_event",
                def = "{'accountId': 1, 'deviceId': 1, 'eventId': 1}", unique = true),
        @CompoundIndex(name = "ix_steps_account_occurred_at", def = "{'accountId': 1, 'occurredAt': 1}")
})
public class StepMeasurement extends BaseDocument {
    private String accountId;
    private String deviceId;
    private String eventId;
    private int stepsDelta;
    private Instant occurredAt;
    private Instant receivedAt;

    public StepMeasurement() {}
    public StepMeasurement(String accountId, String deviceId, String eventId, int stepsDelta,
                           Instant occurredAt, Instant receivedAt) {
        this.accountId = accountId;
        this.deviceId = deviceId;
        this.eventId = eventId;
        this.stepsDelta = stepsDelta;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }
    public String getAccountId() { return accountId; }
    public String getDeviceId() { return deviceId; }
    public String getEventId() { return eventId; }
    public int getStepsDelta() { return stepsDelta; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
