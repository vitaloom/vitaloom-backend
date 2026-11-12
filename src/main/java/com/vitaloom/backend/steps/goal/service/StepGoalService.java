package com.vitaloom.backend.steps.goal.service;

import com.vitaloom.backend.steps.goal.dto.response.StepGoalResponse;

public interface StepGoalService {
    StepGoalResponse setGoal(String accountId, long targetSteps);
    void removeGoal(String accountId);
}
