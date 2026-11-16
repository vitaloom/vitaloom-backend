package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical, immutable provider evidence; never includes account or journal identifiers. */
final class SleepAiEvidenceIdentity {
    private SleepAiEvidenceIdentity() {}

    static Map<String, Object> snapshot(Map<String, Object> values) {
        Map<String, Object> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            map.forEach((key, entry) -> copy.put((String) key, freeze(entry)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(SleepAiEvidenceIdentity::freeze).toList();
        if (value instanceof Number number) return new BigDecimal(decimal(number));
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof LocalDate || value instanceof Period) return value;
        throw new IllegalArgumentException("Unsupported evidence value");
    }

    static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String canonical(Object value) {
        if (value == null) return "null;";
        if (value instanceof Period period) return canonical(List.of(period.start(), period.end(), period.timeZone()));
        if (value instanceof LocalDate day) return canonical(day.toString());
        if (value instanceof String text) return "s" + text.length() + ":" + text;
        if (value instanceof Number number) return "n" + decimal(number) + ";";
        if (value instanceof Boolean flag) return flag ? "true;" : "false;";
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, entry) -> sorted.put((String) key, entry));
            return "m" + sorted.size() + ":" + sorted.entrySet().stream()
                    .map(entry -> canonical(entry.getKey()) + canonical(entry.getValue())).collect(java.util.stream.Collectors.joining());
        }
        if (value instanceof List<?> list) return "l" + list.size() + ":" + list.stream()
                .map(SleepAiEvidenceIdentity::canonical).collect(java.util.stream.Collectors.joining());
        throw new IllegalArgumentException("Unsupported evidence identity value");
    }

    private static String decimal(Number number) {
        return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
    }
}
