package com.vitaloom.backend.statistics.service;

import com.vitaloom.backend.heartrate.service.HeartRateSample;
import com.vitaloom.backend.heartrate.service.HeartRateService;
import com.vitaloom.backend.statistics.dto.response.HeartRateSummaryResponse;
import com.vitaloom.backend.statistics.dto.response.HeartRateTimeSeriesResponse;
import com.vitaloom.backend.statistics.exception.InvalidStatisticsRequestException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {
    public static final ZoneId TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int DAILY_INTERVAL_MINUTES = 15;
    private static final int HOURLY_INTERVAL_MINUTES = 60;

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

    public HeartRateTimeSeriesResponse dailyHeartRateTimeSeries(String accountId, LocalDate date) {
        Instant asOf = clock.instant();
        LocalDate today = asOf.atZone(TIMEZONE).toLocalDate();
        LocalDate selected = date == null ? today : date;
        if (selected.isAfter(today)) throw invalidDate();

        Instant start = selected.atStartOfDay(TIMEZONE).toInstant();
        Instant end = selected.plusDays(1).atStartOfDay(TIMEZONE).toInstant();
        if (!Duration.between(start, end).equals(Duration.ofDays(1))) {
            throw new InvalidStatisticsRequestException("INVALID_REPORT_DATE",
                    "date must be a local calendar day with exactly 24 hours in Asia/Ho_Chi_Minh.");
        }
        HeartRateTimeSeriesAggregator aggregator = new HeartRateTimeSeriesAggregator(
                start, end, DAILY_INTERVAL_MINUTES);
        heartRateService.forEachSampleBetween(accountId, start, end, aggregator::accept);
        return aggregator.toResponse();
    }

    public HeartRateTimeSeriesResponse weeklyHeartRateTimeSeries(String accountId, LocalDate weekStartDate) {
        Instant asOf = clock.instant();
        LocalDate today = asOf.atZone(TIMEZONE).toLocalDate();
        LocalDate currentMonday = today.with(DayOfWeek.MONDAY);
        LocalDate selected = weekStartDate == null ? currentMonday : weekStartDate;
        if (!selected.getDayOfWeek().equals(DayOfWeek.MONDAY) || selected.isAfter(currentMonday)) {
            throw new InvalidStatisticsRequestException("INVALID_WEEK_START_DATE",
                    "weekStartDate must be a Monday no later than the current local week.");
        }

        Instant start = selected.atStartOfDay(TIMEZONE).toInstant();
        Instant end = selected.plusWeeks(1).atStartOfDay(TIMEZONE).toInstant();
        if (!Duration.between(start, end).equals(Duration.ofDays(7))) {
            throw new InvalidStatisticsRequestException("INVALID_WEEK_START_DATE",
                    "weekStartDate must resolve to a 168-hour local week in Asia/Ho_Chi_Minh.");
        }
        HeartRateTimeSeriesAggregator aggregator = new HeartRateTimeSeriesAggregator(
                start, end, HOURLY_INTERVAL_MINUTES);
        heartRateService.forEachSampleBetween(accountId, start, end, sample -> {
            if (!sample.occurredAt().isAfter(asOf)) aggregator.accept(sample);
        });
        return aggregator.toResponse();
    }

    public HeartRateTimeSeriesResponse customHeartRateTimeSeries(
            String accountId, LocalDate startDate, LocalDate endDate) {
        LocalDate today = clock.instant().atZone(TIMEZONE).toLocalDate();
        if (startDate == null || endDate == null || startDate.isAfter(endDate) || endDate.isAfter(today)) {
            throw invalidDateRange();
        }
        long inclusiveDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (inclusiveDays < 1 || inclusiveDays > 30) throw invalidDateRange();

        Instant start = startDate.atStartOfDay(TIMEZONE).toInstant();
        Instant end = endDate.plusDays(1).atStartOfDay(TIMEZONE).toInstant();
        if (!Duration.between(start, end).equals(Duration.ofDays(inclusiveDays))) {
            throw new InvalidStatisticsRequestException("INVALID_DATE_RANGE",
                    "startDate through endDate must cover exactly 24 hours per local calendar day.");
        }
        HeartRateTimeSeriesAggregator aggregator = new HeartRateTimeSeriesAggregator(
                start, end, HOURLY_INTERVAL_MINUTES);
        heartRateService.forEachSampleBetween(accountId, start, end, aggregator::accept);
        return aggregator.toResponse();
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

    private static InvalidStatisticsRequestException invalidDateRange() {
        return new InvalidStatisticsRequestException("INVALID_DATE_RANGE",
                "startDate and endDate must be ordered, end no later than today, and span 1 to 30 calendar days.");
    }
}
