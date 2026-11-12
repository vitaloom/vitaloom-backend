package com.vitaloom.backend.steps.goal.contract;

import com.vitaloom.backend.steps.goal.contract.dto.EffectiveStepGoal;
import java.time.LocalDate;
import java.util.Optional;

public interface StepGoalReadQuery {
    Optional<EffectiveStepGoal> findEffectiveGoal(String accountId, LocalDate date);
}
