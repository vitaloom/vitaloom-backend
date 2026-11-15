package com.vitaloom.backend.statistics.service.impl;

import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.SleepGoalReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsight;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.SleepGoalWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
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
    private final SleepGoalReadQuery goals;
    private final Clock clock;

    public SleepInsightsStatisticsServiceImpl(SleepMainReadQuery mainSleep, SleepGoalReadQuery goals, Clock clock) {
        this.mainSleep = mainSleep;
        this.goals = goals;
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
    public Insights goalInsights(String accountId) {
        Period period = period();
        MainSleepInsightWindow window = read(accountId, period);
        SleepGoalWindow goalWindow = goals.readGoalWindow(accountId, period.start(), period.end());
        var a01 = recentA01(accountId, period);
        var a02 = a02(period, window, goalWindow);
        var b01 = b01(period, window);
        var b04 = b04(period, window, goalWindow);
        return new Insights(period, orderCatalog(List.of(a01, a02, b01, b04), window.records()));
    }

    @Override
    public List<Highlight> recentHighlights(String accountId, Period period, MainSleepWindow a01Window) {
        if (!a01Window.verified()) return List.of();

        Item a01 = recentA01(period, a01Window);
        MainSleepInsightWindow b01Window = read(accountId, period);
        Item b01 = b01(period, b01Window);
        List<SleepRecentHighlightRanker.Candidate> candidates = new ArrayList<>();
        if ("READY".equals(a01.status())) {
            LocalDate freshest = a01Window.records().stream().map(MainSleepDay::day).max(LocalDate::compareTo).orElseThrow();
            candidates.add(new SleepRecentHighlightRanker.Candidate(
                    a01.code(), a01.status(), a01.title(), a01.summary(), freshest, a01Window.records().size()));
        }
        if ("READY".equals(b01.status())) {
            List<MainSleepInsight> rated = b01Window.records().stream()
                    .filter(row -> row.feeling() != null && !row.feeling().isBlank()).toList();
            LocalDate freshest = rated.stream().map(MainSleepInsight::day).max(LocalDate::compareTo).orElseThrow();
            candidates.add(new SleepRecentHighlightRanker.Candidate(
                    b01.code(), b01.status(), b01.title(), b01.summary(), freshest, rated.size()));
        }
        return SleepRecentHighlightRanker.rank(candidates).stream()
                .map(candidate -> new Highlight(candidate.code(), candidate.status(), candidate.title(), candidate.summary()))
                .toList();
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
        if ("A02".equals(code) || "B04".equals(code)) {
            MainSleepInsightWindow window = read(accountId, period);
            SleepGoalWindow goalWindow = goals.readGoalWindow(accountId, period.start(), period.end());
            Item item = "A02".equals(code) ? a02(period, window, goalWindow) : b04(period, window, goalWindow);
            return new Detail(item.code(), item.category(), item.status(), period, item.title(), item.summary(),
                    item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        throw new IllegalArgumentException("Unsupported insight code");
    }

    private Item a02(Period period, MainSleepInsightWindow window, SleepGoalWindow goalWindow) {
        if (!window.verified()) return locked("A02", "A", "UNVERIFIED_DATA", "Goal achievement");
        if (!goalWindow.verified()) return locked("A02", "A", "UNVERIFIED_DATA", "Goal achievement");
        if (window.records().isEmpty()) return locked("A02", "A", "NO_DATA", "Goal achievement");
        List<MainSleepInsight> eligible = window.records().stream()
                .filter(row -> goalWindow.goals().containsKey(row.day())).toList();
        if (eligible.size() < 3) return locked("A02", "A", "INSUFFICIENT_DATA", "Goal achievement");
        long met = eligible.stream().filter(row -> row.minutes() >= goalWindow.goals().get(row.day())).count();
        Map<String, Object> sample = Map.of("eligibleMainDays", eligible.size());
        Map<String, Object> evidence = Map.of("metGoalDays", met, "eligibleMainDays", eligible.size());
        return new Item("A02", "A", "READY", "Goal achievement", true, false,
                "Sleep Goal was met on " + met + " of " + eligible.size() + " eligible Main Sleep days.",
                sample, evidence, eligible.stream().map(MainSleepInsight::id).sorted().toList(),
                List.of("MAIN_SLEEP_ONLY", "HISTORICAL_GOAL_BY_STATISTICAL_DAY", "NO_HEALTH_CLAIM"), POLICY,
                evidenceVersion(period, eligible, goalWindow.goals()));
    }

    private Item b04(Period period, MainSleepInsightWindow window, SleepGoalWindow goalWindow) {
        if (!window.verified()) return locked("B04", "B", "UNVERIFIED_DATA", "Tired despite meeting Goal");
        if (!goalWindow.verified()) return locked("B04", "B", "UNVERIFIED_DATA", "Tired despite meeting Goal");
        if (window.records().isEmpty()) return locked("B04", "B", "NO_DATA", "Tired despite meeting Goal");
        List<MainSleepInsight> rated = window.records().stream()
                .filter(row -> row.feeling() != null && !row.feeling().isBlank()).toList();
        if (rated.size() < 3) return locked("B04", "B", "INSUFFICIENT_DATA", "Tired despite meeting Goal");
        List<MainSleepInsight> comparable = rated.stream()
                .filter(row -> goalWindow.goals().containsKey(row.day())).toList();
        List<MainSleepInsight> exceptions = comparable.stream()
                .filter(row -> "TIRED".equals(row.feeling()) && row.minutes() >= goalWindow.goals().get(row.day()))
                .toList();
        if (exceptions.isEmpty()) return locked("B04", "B", "INSUFFICIENT_DATA", "Tired despite meeting Goal");
        List<Map<String, Object>> exceptionEvidence = exceptions.stream()
                .map(row -> Map.<String, Object>of("day", row.day(), "minutes", row.minutes(),
                        "goalMinutes", goalWindow.goals().get(row.day())))
                .toList();
        Map<String, Object> sample = Map.of("ratedMain", rated.size(), "comparableRatedMain", comparable.size());
        Map<String, Object> evidence = Map.of(
                "exceptionCount", exceptions.size(), "ratedMain", rated.size(), "exceptions", exceptionEvidence);
        return new Item("B04", "B", "READY", "Tired despite meeting Goal", true, false,
                "Reported tired despite meeting the historical Sleep Goal on " + exceptions.size()
                        + " of " + rated.size() + " rated Main Sleep days.",
                sample, evidence, rated.stream().map(MainSleepInsight::id).sorted().toList(),
                List.of("SELF_REPORTED_FEELING", "HISTORICAL_GOAL_BY_STATISTICAL_DAY", "NO_HEALTH_CLAIM"), POLICY,
                evidenceVersion(period, rated, goalWindow.goals()));
    }

    private static List<Item> orderCatalog(List<Item> items, List<MainSleepInsight> records) {
        Map<String, MainSleepInsight> byId = new java.util.HashMap<>();
        records.forEach(row -> byId.put(row.id(), row));
        return items.stream()
                .sorted((left, right) -> {
                    int category = left.category().compareTo(right.category());
                    if (category != 0) return category;
                    boolean leftReady = "READY".equals(left.status());
                    boolean rightReady = "READY".equals(right.status());
                    if (leftReady != rightReady) return leftReady ? -1 : 1;
                    if (!leftReady) return left.code().compareTo(right.code());
                    LocalDate leftFreshest = freshest(left, byId, records);
                    LocalDate rightFreshest = freshest(right, byId, records);
                    int freshness = rightFreshest.compareTo(leftFreshest);
                    if (freshness != 0) return freshness;
                    int sample = Integer.compare(sampleSize(right), sampleSize(left));
                    return sample != 0 ? sample : left.code().compareTo(right.code());
                })
                .toList();
    }

    private static LocalDate freshest(Item item, Map<String, MainSleepInsight> byId, List<MainSleepInsight> records) {
        if ("A01".equals(item.code())) {
            return records.stream().map(MainSleepInsight::day).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        }
        return item.sourceRefs().stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(MainSleepInsight::day).max(LocalDate::compareTo).orElse(LocalDate.MIN);
    }

    private static int sampleSize(Item item) {
        String field = switch (item.code()) {
            case "A01" -> "recordedMainDays";
            case "A02" -> "eligibleMainDays";
            case "B01", "B04" -> "ratedMain";
            default -> "";
        };
        Object value = item.sample().get(field);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String evidenceVersion(Period period, List<MainSleepInsight> records, Map<LocalDate, Integer> goals) {
        String source = period.start() + "|" + period.end() + "|" + period.timeZone() + "\n" + records.stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .map(row -> row.id() + "|" + row.day() + "|" + row.minutes() + "|" + row.feeling()
                        + "|" + row.confirmedAt() + "|" + goals.get(row.day()))
                .reduce("", (left, right) -> left + "\n" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
        return recentA01(period, window);
    }

    private Item recentA01(Period period, MainSleepWindow window) {
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
