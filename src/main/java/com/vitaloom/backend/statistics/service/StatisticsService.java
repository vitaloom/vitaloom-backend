package com.vitaloom.backend.statistics.service;

import com.vitaloom.backend.heartrate.service.HeartRateSample;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {
    public static final ZoneId TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final HeartRateService heartRateService;
    private final Clock clock;

    @Autowired
    public StatisticsService(HeartRateService heartRateService) {
        this(heartRateService, Clock.systemUTC());
    }

    StatisticsService(HeartRateService heartRateService, Clock clock) {
        this.heartRateService = heartRateService;
        this.clock = clock;
    }

    public HeartRateSummaryResponse heartRateSummary(String accountId, String date) {
        Instant asOf = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(TIMEZONE));
        LocalDate selected = parseDate(date, today);

        var latest = heartRateService.findLatestSample(accountId)
                .map(sample -> new HeartRateSummaryResponse.LatestRecorded(
                        sample.heartRateBpm(), sample.occurredAt()))
                .orElse(null);

        Instant startInclusive = selected.atStartOfDay(TIMEZONE).toInstant();
        Instant endExclusive = selected.plusDays(1).atStartOfDay(TIMEZONE).toInstant();
        List<HeartRateSample> samples = heartRateService.findSamplesBetween(
                accountId, startInclusive, endExclusive);

        Double average = samples.isEmpty()
                ? null
                : samples.stream().mapToInt(HeartRateSample::heartRateBpm).average().orElseThrow();
        String dataStatus = selected.equals(today) ? "CURRENT_DAY_PARTIAL" : "HISTORY_UNVERIFIED";

        return new HeartRateSummaryResponse(
                selected,
                TIMEZONE.getId(),
                asOf,
                latest,
                new HeartRateSummaryResponse.DailyAverage(average, dataStatus));
    }

    private static LocalDate parseDate(String date, LocalDate today) {
        if (date == null) return today;

        try {
            LocalDate selected = LocalDate.parse(date);
            if (selected.isAfter(today) || !selected.toString().equals(date)) {
                throw invalidDate();
            }
            return selected;
        } catch (DateTimeParseException exception) {
            throw invalidDate();
        }
    }

    private static InvalidStatisticsRequestException invalidDate() {
        return new InvalidStatisticsRequestException(
                "INVALID_REPORT_DATE",
                "date must be a valid YYYY-MM-DD date that is not in the future.");
    }
}
