package com.vitaloom.backend.water.service.impl;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.dto.response.WeeklyWaterProgressResponse;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.water.model.WaterLog;
import com.vitaloom.backend.water.repository.WaterLogRepository;
import com.vitaloom.backend.water.service.WaterService;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WaterServiceImpl implements WaterService {
    private static final int DEFAULT_GOAL_ML = 2000;

    private final WaterLogRepository logs;
    private final Clock clock;

    public DailyWaterSummaryResponse dailySummary(String userId, String dateText, String timeZone) {
        LocalDate date = parseDate(dateText);
        ZoneId zone = parseZone(timeZone);
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<WaterLog> dayLogs = logs.findByUserIdAndLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(
                userId, start, end);
        int total = dayLogs.stream().mapToInt(WaterLog::getAmountMl).sum();
        return new DailyWaterSummaryResponse(date.toString(), DEFAULT_GOAL_ML, total,
                total * 100.0 / DEFAULT_GOAL_ML, dayLogs.stream().map(WaterLogResponse::from).toList());
    }

    public WeeklyWaterProgressResponse weeklyProgress(String userId, String weekStartText, String timeZone) {
        LocalDate weekStart = parseDate(weekStartText);
        if (weekStart.getDayOfWeek().getValue() != 1) throw new WaterValidationException();
        ZoneId zone = parseZone(timeZone);
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant start = weekStart.atStartOfDay(zone).toInstant();
        Instant end = weekStart.plusDays(7).atStartOfDay(zone).toInstant();
        List<WaterLog> weekLogs = logs.findByUserIdAndLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(
                userId, start, end);
        Map<LocalDate, Integer> totals = new HashMap<>();
        for (WaterLog log : weekLogs) {
            LocalDate localDate = log.getLoggedAt().atZone(zone).toLocalDate();
            totals.merge(localDate, log.getAmountMl(), Integer::sum);
        }
        List<WeeklyWaterProgressResponse.Day> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            days.add(new WeeklyWaterProgressResponse.Day(date.toString(), totals.getOrDefault(date, 0), DEFAULT_GOAL_ML));
        }
        return new WeeklyWaterProgressResponse(weekStart.toString(), weekEnd.toString(), days);
    }

    public WaterLogResponse create(String userId, Integer amountMl, String loggedAtText) {
        if (amountMl == null || amountMl < 1 || amountMl > 2000) {
            throw new WaterValidationException();
        }
        Instant serverNow = clock.instant();
        Instant loggedAt;
        if (loggedAtText == null) {
            loggedAt = serverNow.truncatedTo(ChronoUnit.MILLIS);
        } else {
            try {
                loggedAt = OffsetDateTime.parse(loggedAtText).toInstant();
            } catch (DateTimeException exception) {
                throw new WaterValidationException();
            }
            if (loggedAt.isAfter(serverNow)) {
                throw new WaterValidationException("loggedAt cannot be in the future.");
            }
        }
        return WaterLogResponse.from(logs.save(new WaterLog(userId, amountMl, loggedAt)));
    }

    private static LocalDate parseDate(String value) {
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (value == null || !parsed.toString().equals(value)) throw new DateTimeException("Invalid date format");
            return parsed;
        } catch (DateTimeException | NullPointerException exception) {
            throw new WaterValidationException();
        }
    }

    private static ZoneId parseZone(String timeZone) {
        try {
            if (timeZone == null || timeZone.isBlank()) throw new DateTimeException("Timezone is required");
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw new WaterValidationException();
        }
    }
}
