package com.vitaloom.backend.sleep.contract;

import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeWindow;
import java.time.LocalDate;

public interface SleepMainReadQuery {
    MainSleepWindow readWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive);
    MainSleepInsightWindow readInsightWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive);
    MainSleepRangeWindow readRangeWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive);
}
