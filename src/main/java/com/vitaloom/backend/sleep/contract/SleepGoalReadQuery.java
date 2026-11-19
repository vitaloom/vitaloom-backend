package com.vitaloom.backend.sleep.contract;

import com.vitaloom.backend.sleep.contract.dto.SleepGoalWindow;
import java.time.LocalDate;

public interface SleepGoalReadQuery {
    SleepGoalWindow readGoalWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive);
}
