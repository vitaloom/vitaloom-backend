package com.vitaloom.backend.statistics.service.impl;

import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService.Report;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SleepRecentStatisticsServiceImpl implements SleepRecentStatisticsService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int WINDOW_DAYS = 7;

    private final SleepMainReadQuery mainSleep;
    private final Clock clock;

    public SleepRecentStatisticsServiceImpl(SleepMainReadQuery mainSleep, Clock clock) {
        this.mainSleep = mainSleep;
        this.clock = clock;
    }

    @Override
    public Report recent(String accountId) {
        LocalDate end = clock.instant().atZone(REPORT_ZONE).toLocalDate();
        LocalDate start = end.minusDays(WINDOW_DAYS - 1L);
        MainSleepWindow window = mainSleep.readWindow(accountId, start, end);
        List<Day> days = new ArrayList<>(WINDOW_DAYS);
        int minutesTotal = 0;
        int recordedDays = 0;
        for (int offset = 0; offset < WINDOW_DAYS; offset++) {
            LocalDate day = start.plusDays(offset);
            MainSleepDay record = window.records().stream().filter(value -> value.day().equals(day))
                    .findFirst().orElse(null);
            if (record != null) {
                days.add(new Day(day, "RECORDED", record.minutes()));
                minutesTotal += record.minutes();
                recordedDays++;
            } else {
                days.add(new Day(day,
                        window.verified() ? "NOT_RECORDED" : "UNVERIFIED", null));
            }
        }

        if (!window.verified()) {
            return new Report(
                    new Period(start, end, REPORT_ZONE.getId()),
                    "UNVERIFIED_DATA", days, null, WINDOW_DAYS, null, List.of());
        }

        Integer average = recordedDays == 0 ? null : (minutesTotal * 2 + recordedDays) / (2 * recordedDays);
        String dataStatus = recordedDays == 0 ? "NO_DATA" : "VERIFIED";
        List<Insight> insights = recordedDays < 3 ? List.of() : List.of(
                new Insight("A01", "READY", recordedDays, WINDOW_DAYS));
        return new Report(
                new Period(start, end, REPORT_ZONE.getId()),
                dataStatus, days, recordedDays, WINDOW_DAYS, average, insights);
    }
}
