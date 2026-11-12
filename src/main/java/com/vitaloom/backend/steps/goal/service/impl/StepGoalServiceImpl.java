package com.vitaloom.backend.steps.goal.service.impl;

import com.vitaloom.backend.steps.exception.InvalidStepsRequestException;
import com.vitaloom.backend.steps.goal.contract.StepGoalReadQuery;
import com.vitaloom.backend.steps.goal.contract.dto.EffectiveStepGoal;
import com.vitaloom.backend.steps.goal.dto.response.StepGoalResponse;
import com.vitaloom.backend.steps.goal.repository.StepGoalRepository;
import com.vitaloom.backend.steps.goal.service.StepGoalService;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StepGoalServiceImpl implements StepGoalService, StepGoalReadQuery {
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    private final StepGoalRepository repository;
    private final Clock clock;
    private final ZoneId timeZone;

    @Override
    public StepGoalResponse setGoal(String accountId, long targetSteps) {
        if (targetSteps <= 0 || BigInteger.valueOf(targetSteps).compareTo(MAX_SAFE_INTEGER) > 0) {
            throw invalidGoal();
        }
        LocalDate effectiveDate = LocalDate.now(clock.withZone(timeZone));
        repository.upsertSnapshot(accountId, effectiveDate, targetSteps, false);
        return new StepGoalResponse(targetSteps, effectiveDate.toString());
    }

    @Override
    public void removeGoal(String accountId) {
        LocalDate effectiveDate = LocalDate.now(clock.withZone(timeZone));
        repository.upsertSnapshot(accountId, effectiveDate, null, true);
    }

    @Override
    public Optional<EffectiveStepGoal> findEffectiveGoal(String accountId, LocalDate date) {
        return repository.latestOnOrBefore(accountId, date)
                .filter(snapshot -> !snapshot.isRemoved())
                .map(snapshot -> new EffectiveStepGoal(snapshot.getTargetSteps(), snapshot.getEffectiveDate()));
    }

    private static InvalidStepsRequestException invalidGoal() {
        return new InvalidStepsRequestException("INVALID_STEP_GOAL", "targetSteps must be a positive safe integer.");
    }
}
