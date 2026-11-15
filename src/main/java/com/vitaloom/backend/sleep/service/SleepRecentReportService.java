package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.sleep.dto.response.SleepRecentReportResponse;
import java.time.ZoneId;

public interface SleepRecentReportService {
    SleepRecentReportResponse recent(String accountId, boolean full, ZoneId selectedZone);
}
