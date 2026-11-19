package com.vitaloom.backend.statistics.service.impl;

import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeWindow;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class SleepTimingInsight {
    private static final String POLICY = "sleep-recent-mvp-2026-11-14";

    private SleepTimingInsight() {}

    static List<String> availableTimeZones(MainSleepRangeWindow window) {
        if (!window.verified()) return List.of();
        return window.records().stream()
                .filter(row -> "RANGE".equals(row.method()))
                .map(MainSleepRangeDay::timeZone)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    static Item evaluate(Period period, MainSleepRangeWindow window, ZoneId selectedZone) {
        if (!window.verified()) return locked("UNVERIFIED_DATA");
        if (window.records().isEmpty()) return locked("NO_DATA");
        if (selectedZone == null) return locked("INSUFFICIENT_DATA");

        List<MainSleepRangeDay> selected = window.records().stream()
                .filter(row -> "RANGE".equals(row.method()))
                .filter(row -> selectedZone.getId().equals(row.timeZone()))
                .sorted(Comparator.comparing(MainSleepRangeDay::day))
                .toList();
        if (hasTransitionInWindow(selectedZone, period.start(), period.end())) {
            return locked("INSUFFICIENT_DATA");
        }
        List<MainSleepRangeDay> eligible = selected.stream()
                .filter(row -> !crossesTransition(selectedZone, row.startAt(), row.endAt()))
                .toList();
        if (eligible.size() < 3) return locked("INSUFFICIENT_DATA");

        List<Integer> bedtimes = eligible.stream().map(row -> localMinute(row.startAt(), row.day(), selectedZone)).toList();
        List<Integer> wakeTimes = eligible.stream().map(row -> localMinute(row.endAt(), row.day(), selectedZone)).toList();
        int bedtimeMin = bedtimes.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int bedtimeMax = bedtimes.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int wakeMin = wakeTimes.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int wakeMax = wakeTimes.stream().mapToInt(Integer::intValue).max().orElseThrow();
        Map<String, Object> evidence = Map.of(
                "timeZone", selectedZone.getId(),
                "bedtime", Map.of("minMinute", bedtimeMin, "maxMinute", bedtimeMax,
                        "spanMinutes", bedtimeMax - bedtimeMin),
                "wake", Map.of("minMinute", wakeMin, "maxMinute", wakeMax,
                        "spanMinutes", wakeMax - wakeMin));
        List<String> sourceRefs = eligible.stream().map(MainSleepRangeDay::sourceId).sorted().toList();
        String versionSource = period + "|" + selectedZone.getId() + "\n" + eligible.stream()
                .map(row -> row.sourceId() + "|" + row.day() + "|" + row.startAt() + "|" + row.endAt())
                .reduce("", (left, right) -> left + "\n" + right);
        return new Item("A04", "A", "READY", "Bedtime and wake variation", true, false,
                "Observed bedtime and wake-time ranges across " + eligible.size() + " Main Sleep RANGE days.",
                Map.of("eligibleRangeDays", eligible.size()), evidence, sourceRefs,
                List.of("MAIN_RANGE_ONLY", "USER_SELECTED_TIME_ZONE", "DST_EXCLUDED", "DESCRIPTIVE_ONLY", "NO_HEALTH_CLAIM"),
                POLICY, sha256(versionSource));
    }

    private static Item locked(String status) {
        return new Item("A04", "A", status, "Bedtime and wake variation", false, true,
                null, null, null, null, null, null, null);
    }

    private static int localMinute(Instant instant, LocalDate wakeDay, ZoneId zone) {
        var local = instant.atZone(zone);
        return Math.toIntExact((local.toLocalDate().toEpochDay() - wakeDay.toEpochDay()) * 1440L
                + local.getHour() * 60L + local.getMinute());
    }

    private static boolean hasTransitionInWindow(ZoneId zone, LocalDate start, LocalDate end) {
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(zone).toInstant();
        ZoneOffsetTransition transition = zone.getRules().nextTransition(from.minusNanos(1));
        return transition != null && !transition.getInstant().isBefore(from) && transition.getInstant().isBefore(until);
    }

    private static boolean crossesTransition(ZoneId zone, Instant start, Instant end) {
        ZoneOffsetTransition transition = zone.getRules().nextTransition(start.minusNanos(1));
        return transition != null && transition.getInstant().isAfter(start) && !transition.getInstant().isAfter(end);
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
