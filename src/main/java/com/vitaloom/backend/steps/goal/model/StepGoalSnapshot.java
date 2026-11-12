package com.vitaloom.backend.steps.goal.model;

import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Document("step_goal_snapshots")
@CompoundIndex(name = "uk_step_goal_account_effective_date",
        def = "{'account_id': 1, 'effective_date': 1}", unique = true)
@Getter
@NoArgsConstructor
public class StepGoalSnapshot extends BaseDocument {
    @Field("account_id") private String accountId;
    @Field("effective_date") private String effectiveDate;
    @Field("target_steps") private Long targetSteps;
    @Field("removed") private boolean removed;

    public StepGoalSnapshot(String accountId, String effectiveDate, Long targetSteps, boolean removed) {
        this.accountId = accountId;
        this.effectiveDate = effectiveDate;
        this.targetSteps = targetSteps;
        this.removed = removed;
    }
}
