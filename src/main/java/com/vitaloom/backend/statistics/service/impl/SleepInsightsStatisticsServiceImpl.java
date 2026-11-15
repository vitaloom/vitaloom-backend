package com.vitaloom.backend.statistics.service.impl;

import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsight;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SleepInsightsStatisticsServiceImpl implements SleepInsightsStatisticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String POLICY = "sleep-recent-mvp-2026-11-14";
    private static final List<String> LIMITATIONS = List.of("SELF_REPORTED_FEELING", "DESCRIPTIVE_ONLY", "NO_HEALTH_CLAIM");
    private final SleepMainReadQuery mainSleep;
    private final Clock clock;

    public SleepInsightsStatisticsServiceImpl(SleepMainReadQuery mainSleep, Clock clock) {
        this.mainSleep = mainSleep;
        this.clock = clock;
    }

    @Override
    public Insights insights(String accountId) {
        Period period = period();
        MainSleepInsightWindow window = read(accountId, period);
        var a01 = recentA01(accountId, period);
        var b01 = b01(period, window);
        return new Insights(period, List.of(a01, b01));
    }

    @Override
    public Detail detail(String accountId, String code) {
        Period period = period();
        if ("A01".equals(code)) {
            var item = recentA01(accountId, period);
            return new Detail(item.code(), item.category(), item.status(), period, item.title(), item.summary(),
                    item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        if ("B01".equals(code)) {
            var item = b01(period, read(accountId, period));
            return new Detail(item.code(), item.category(), item.status(), period, item.title(), item.summary(),
                    item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        throw new IllegalArgumentException("Unsupported insight code");
    }

    private MainSleepInsightWindow read(String accountId, Period period) {
        return mainSleep.readInsightWindow(accountId, period.start(), period.end());
    }

    private Period period() {
        LocalDate end = clock.instant().atZone(ZONE).toLocalDate();
        return new Period(end.minusDays(6), end, ZONE.getId());
    }

    private Item recentA01(String accountId, Period period) {
        var window = mainSleep.readWindow(accountId, period.start(), period.end());
        if (!window.verified()) return locked("A01", "A", "UNVERIFIED_DATA", "Recording coverage");
        int days = window.records().size();
        if (days == 0) return locked("A01", "A", "NO_DATA", "Recording coverage");
        if (days < 3) return locked("A01", "A", "INSUFFICIENT_DATA", "Recording coverage");
        return new Item("A01", "A", "READY", "Recording coverage", true, false,
                "Main Sleep was recorded on " + days + " of 7 days.", Map.of("recordedMainDays", days, "coverageDenominator", 7),
                Map.of("recordedMainDays", days, "coverageDenominator", 7), List.of("sleep_sessions:MAIN:statistical_day"),
                List.of("COVERAGE_IS_NOT_SLEEP_DURATION"), POLICY, evidenceVersionDays(window.records()));
    }

    private Item b01(Period period, MainSleepInsightWindow window) {
        if (!window.verified()) return locked("B01", "B", "UNVERIFIED_DATA", "Feeling distribution");
        List<MainSleepInsight> rated = window.records().stream()
                .filter(row -> row.feeling() != null && !row.feeling().isBlank()).toList();
        long missing = window.records().size() - rated.size();
        if (window.records().isEmpty()) return locked("B01", "B", "NO_DATA", "Feeling distribution");
        if (rated.size() < 3) return locked("B01", "B", "INSUFFICIENT_DATA", "Feeling distribution");
        long comfortable = count(rated, "COMFORTABLE");
        long normal = count(rated, "NORMAL");
        long tired = count(rated, "TIRED");
        int denominator = rated.size();
        Map<String, Object> sample = Map.of("ratedMain", denominator, "mainWithoutFeeling", missing);
        Map<String, Object> evidence = Map.of(
                "comfortable", distribution(comfortable, denominator),
                "normal", distribution(normal, denominator), "tired", distribution(tired, denominator),
                "mainWithoutFeeling", missing);
        List<String> refs = window.records().stream().map(MainSleepInsight::id).sorted().toList();
        return new Item("B01", "B", "READY", "Feeling distribution", true, false,
                "Self-reported feelings across " + denominator + " Main Sleep sessions.", sample, evidence,
                refs, LIMITATIONS, POLICY, evidenceVersion(window.records()));
    }

    private static Map<String, Object> distribution(long count, int denominator) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", count);
        values.put("denominator", denominator);
        values.put("percentage", Math.round(count * 10000.0 / denominator) / 100.0);
        return Map.copyOf(values);
    }

    private static long count(List<MainSleepInsight> rows, String feeling) {
        return rows.stream().filter(row -> feeling.equals(row.feeling())).count();
    }

    private static Item locked(String code, String category, String status, String title) {
        return new Item(code, category, status, title, false, true, null, null, null, null, null, null, null);
    }

    private static String evidenceVersion(List<MainSleepInsight> rows) {
        String source = rows.stream().sorted((a, b) -> a.id().compareTo(b.id()))
                .map(row -> row.id() + "|" + row.day() + "|" + row.feeling() + "|" + row.confirmedAt())
                .reduce("", (a, b) -> a + "\n" + b);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String evidenceVersionDays(List<MainSleepDay> rows) {
        String source = rows.stream().sorted((a, b) -> a.sourceId().compareTo(b.sourceId()))
                .map(row -> row.sourceId() + "|" + row.day() + "|" + row.minutes())
                .reduce("", (a, b) -> a + "\n" + b);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
