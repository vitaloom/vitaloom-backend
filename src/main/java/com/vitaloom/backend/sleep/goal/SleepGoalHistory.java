package com.vitaloom.backend.sleep.goal;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("sleep_goal_history")
public record SleepGoalHistory(
        @Id String accountId,
        long revision,
        List<Snapshot> snapshots) {
    public record Snapshot(
            @Field("effective_day") String effectiveDay,
            Integer minutes,
            boolean removed) {
        public LocalDate day() { return LocalDate.parse(effectiveDay); }
    }
}
