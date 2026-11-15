package com.vitaloom.backend.sleep.contract;

import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import java.time.LocalDate;

public interface SleepMainReadQuery {
    MainSleepWindow readWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive);
}
