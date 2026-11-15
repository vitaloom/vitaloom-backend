package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;

public interface SleepRecentReportService {
    SleepRecentReportResponse recent(String accountId);
}
