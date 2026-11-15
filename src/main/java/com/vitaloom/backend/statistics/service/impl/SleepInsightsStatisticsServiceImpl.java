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
    public Insights trendInsights(String accountId) {
        Period current = period();
        Period recent = trendRecentPeriod(current);
        Period baseline = new Period(recent.start().minusDays(7), recent.start().minusDays(1), ZONE.getId());
        var recentDuration = mainSleep.readWindow(accountId, recent.start(), recent.end());
        var baselineDuration = mainSleep.readWindow(accountId, baseline.start(), baseline.end());
        var recentFeelings = mainSleep.readInsightWindow(accountId, recent.start(), recent.end());
        var baselineFeelings = mainSleep.readInsightWindow(accountId, baseline.start(), baseline.end());
        var recentWindow = read(accountId, current);
        var goalWindow = goals.readGoalWindow(accountId, current.start(), current.end());
        var currentDuration = mainSleep.readWindow(accountId, current.start(), current.end());
        var a01 = recentA01(current, currentDuration);
        var a02 = a02(current, recentWindow, goalWindow);
        var b01 = b01(current, recentWindow);
        var b04 = b04(current, recentWindow, goalWindow);
        var a03 = durationTrend(recent, baseline, recentDuration, baselineDuration);
        var b03 = feelingTrend(recent, baseline, recentFeelings, baselineFeelings);
        List<MainSleepInsight> records = new ArrayList<>(recentWindow.records());
        records.addAll(baselineFeelings.records());
        List<MainSleepDay> durations = new ArrayList<>(recentDuration.records());
        durations.addAll(baselineDuration.records());
        List<Item> items = new ArrayList<>(List.of(a01, a02, a03, b01, b03, b04));
        return new Insights(current, orderCatalog(items, records, durations, currentDuration.records()));
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
            MainSleepWindow window = mainSleep.readWindow(accountId, period.start(), period.end());
            var item = recentA01(period, window);
            Map<String, Object> evidence = item.evidence();
            if ("READY".equals(item.status())) {
                evidence = withDays(evidence, a01Days(period, window));
            }
            return new Detail(item.code(), item.category(), item.status(), period, item.title(), item.summary(),
                    item.sample(), evidence, item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
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
            Map<String, Object> evidence = item.evidence();
            if ("A02".equals(code) && "READY".equals(item.status())) {
                evidence = withDays(evidence, a02Days(period, window, goalWindow));
            }
            return new Detail(item.code(), item.category(), item.status(), period, item.title(), item.summary(),
                    item.sample(), evidence, item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        if ("A03".equals(code)) {
            Period recent = trendRecentPeriod(period);
            Period baseline = new Period(recent.start().minusDays(7), recent.start().minusDays(1), ZONE.getId());
            Item item = durationTrend(recent, baseline,
                    mainSleep.readWindow(accountId, recent.start(), recent.end()),
                    mainSleep.readWindow(accountId, baseline.start(), baseline.end()));
            return new Detail(item.code(), item.category(), item.status(), recent, item.title(), item.summary(),
                    item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        if ("B03".equals(code)) {
            Period recent = trendRecentPeriod(period);
            Period baseline = new Period(recent.start().minusDays(7), recent.start().minusDays(1), ZONE.getId());
            Item item = feelingTrend(recent, baseline,
                    mainSleep.readInsightWindow(accountId, recent.start(), recent.end()),
                    mainSleep.readInsightWindow(accountId, baseline.start(), baseline.end()));
            return new Detail(item.code(), item.category(), item.status(), recent, item.title(), item.summary(),
                    item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
        }
        throw new IllegalArgumentException("Unsupported insight code");
    }

    private Item durationTrend(Period recent, Period baseline, MainSleepWindow recentWindow, MainSleepWindow baselineWindow) {
        if (!recentWindow.verified() || !baselineWindow.verified()) return locked("A03", "A", "UNVERIFIED_DATA", "Duration trend");
        List<MainSleepDay> recentRows = recentWindow.records();
        List<MainSleepDay> baselineRows = baselineWindow.records();
        if (recentRows.isEmpty() && baselineRows.isEmpty()) return locked("A03", "A", "NO_DATA", "Duration trend");
        if (recentRows.size() < 3 || baselineRows.size() < 3) return locked("A03", "A", "INSUFFICIENT_DATA", "Duration trend");
        int recentMean = meanMinutes(recentRows);
        int baselineMean = meanMinutes(baselineRows);
        Map<String, Object> evidence = Map.of("baseline", Map.of("period", baseline, "meanMinutes", baselineMean),
                "recent", Map.of("period", recent, "meanMinutes", recentMean), "deltaMinutes", recentMean - baselineMean);
        List<MainSleepDay> sources = new ArrayList<>(baselineRows);
        sources.addAll(recentRows);
        return new Item("A03", "A", "READY", "Duration trend", true, false,
                "Mean Main Sleep duration changed by " + signed(recentMean - baselineMean) + " minutes.",
                Map.of("baselineMainDays", baselineRows.size(), "recentMainDays", recentRows.size()), evidence,
                sources.stream().map(MainSleepDay::sourceId).sorted().toList(),
                List.of("DESCRIPTIVE_ONLY", "NO_HEALTH_CLAIM"), POLICY,
                durationTrendVersion(recent, baseline, sources));
    }

    private Item feelingTrend(Period recent, Period baseline, MainSleepInsightWindow recentWindow,
            MainSleepInsightWindow baselineWindow) {
        if (!recentWindow.verified() || !baselineWindow.verified()) return locked("B03", "B", "UNVERIFIED_DATA", "Feeling trend");
        List<MainSleepInsight> recentRows = recentWindow.records();
        List<MainSleepInsight> baselineRows = baselineWindow.records();
        if (recentRows.isEmpty() && baselineRows.isEmpty()) return locked("B03", "B", "NO_DATA", "Feeling trend");
        List<MainSleepInsight> recentRated = recentRows.stream().filter(SleepInsightsStatisticsServiceImpl::hasFeeling).toList();
        List<MainSleepInsight> baselineRated = baselineRows.stream().filter(SleepInsightsStatisticsServiceImpl::hasFeeling).toList();
        if (recentRated.size() < 3 || baselineRated.size() < 3) return locked("B03", "B", "INSUFFICIENT_DATA", "Feeling trend");
        Map<String, Object> base = feelingDistribution(baselineRated);
        Map<String, Object> latest = feelingDistribution(recentRated);
        Map<String, Object> deltas = new LinkedHashMap<>();
        for (String feeling : List.of("comfortable", "normal", "tired")) {
            Map<?, ?> before = (Map<?, ?>) base.get(feeling);
            Map<?, ?> after = (Map<?, ?>) latest.get(feeling);
            double beforeShare = ((Number) before.get("count")).doubleValue() / ((Number) before.get("denominator")).doubleValue();
            double afterShare = ((Number) after.get("count")).doubleValue() / ((Number) after.get("denominator")).doubleValue();
            deltas.put(feeling, Math.round((afterShare - beforeShare) * 10000.0) / 100.0);
        }
        Map<String, Object> baselineEvidence = new LinkedHashMap<>(trendFeelingPeriod(baseline, base));
        baselineEvidence.put("ratedMain", baselineRated.size());
        baselineEvidence.put("mainWithoutFeeling", baselineRows.size() - baselineRated.size());
        Map<String, Object> recentEvidence = new LinkedHashMap<>(trendFeelingPeriod(recent, latest));
        recentEvidence.put("ratedMain", recentRated.size());
        recentEvidence.put("mainWithoutFeeling", recentRows.size() - recentRated.size());
        Map<String, Object> evidence = Map.of("baseline", Map.copyOf(baselineEvidence),
                "recent", Map.copyOf(recentEvidence), "deltaPercentagePoints", Map.copyOf(deltas));
        List<MainSleepInsight> sources = new ArrayList<>(baselineRated);
        sources.addAll(recentRated);
        return new Item("B03", "B", "READY", "Feeling trend", true, false,
                "Self-reported feeling shares changed across two completed periods.",
                Map.of("baselineRatedMain", baselineRated.size(), "recentRatedMain", recentRated.size(),
                        "baselineMainWithoutFeeling", baselineRows.size() - baselineRated.size(),
                        "recentMainWithoutFeeling", recentRows.size() - recentRated.size()), evidence,
                sources.stream().map(MainSleepInsight::id).sorted().toList(), LIMITATIONS, POLICY,
                feelingTrendVersion(recent, baseline, recentRows, baselineRows));
    }

    private static Map<String, Object> trendFeelingPeriod(Period period, Map<String, Object> distribution) {
        Map<String, Object> result = new LinkedHashMap<>(distribution);
        result.put("period", period);
        return Map.copyOf(result);
    }

    private static Map<String, Object> feelingDistribution(List<MainSleepInsight> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String feeling : List.of("COMFORTABLE", "NORMAL", "TIRED")) {
            String key = feeling.toLowerCase(java.util.Locale.ROOT);
            result.put(key, distribution(count(rows, feeling), rows.size()));
        }
        return Map.copyOf(result);
    }

    private static boolean hasFeeling(MainSleepInsight row) { return row.feeling() != null && !row.feeling().isBlank(); }

    private static int meanMinutes(List<MainSleepDay> rows) {
        long total = rows.stream().mapToLong(MainSleepDay::minutes).sum();
        return (int) ((total + rows.size() / 2L) / rows.size());
    }

    private static String signed(int value) { return value > 0 ? "+" + value : Integer.toString(value); }

    private static Period trendRecentPeriod(Period current) {
        LocalDate end = current.end().minusDays(1);
        return new Period(end.minusDays(6), end, current.timeZone());
    }

    private static String durationTrendVersion(Period recent, Period baseline, List<MainSleepDay> rows) {
        String source = recent + "|" + baseline + "\n" + rows.stream().sorted((a, b) -> a.sourceId().compareTo(b.sourceId()))
                .map(row -> row.sourceId() + "|" + row.day() + "|" + row.minutes()).reduce("", (a, b) -> a + "\n" + b);
        return sha256(source);
    }

    private static String feelingTrendVersion(Period recent, Period baseline,
            List<MainSleepInsight> recentRows, List<MainSleepInsight> baselineRows) {
        List<MainSleepInsight> all = new ArrayList<>(recentRows);
        all.addAll(baselineRows);
        String source = recent + "|" + baseline + "\n" + all.stream().sorted((a, b) -> a.id().compareTo(b.id()))
                .map(row -> row.id() + "|" + row.day() + "|" + row.feeling() + "|" + row.confirmedAt())
                .reduce("", (a, b) -> a + "\n" + b);
        return sha256(source);
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
                a02EvidenceVersion(period, eligible, goalWindow, a02Days(period, window, goalWindow)));
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
        return orderCatalog(items, records, List.of(), List.of());
    }

    private static List<Item> orderCatalog(List<Item> items, List<MainSleepInsight> records,
            List<MainSleepDay> durationRows) {
        return orderCatalog(items, records, durationRows, List.of());
    }

    private static List<Item> orderCatalog(List<Item> items, List<MainSleepInsight> records,
            List<MainSleepDay> durationRows, List<MainSleepDay> currentDurationRows) {
        Map<String, MainSleepInsight> byId = new java.util.HashMap<>();
        records.forEach(row -> byId.put(row.id(), row));
        Map<String, MainSleepDay> durationsById = new java.util.HashMap<>();
        durationRows.forEach(row -> durationsById.put(row.sourceId(), row));
        return items.stream()
                .sorted((left, right) -> {
                    int category = left.category().compareTo(right.category());
                    if (category != 0) return category;
                    boolean leftReady = "READY".equals(left.status());
                    boolean rightReady = "READY".equals(right.status());
                    if (leftReady != rightReady) return leftReady ? -1 : 1;
                    if (!leftReady) return left.code().compareTo(right.code());
                    LocalDate leftFreshest = freshest(left, byId, durationsById, records, currentDurationRows);
                    LocalDate rightFreshest = freshest(right, byId, durationsById, records, currentDurationRows);
                    int freshness = rightFreshest.compareTo(leftFreshest);
                    if (freshness != 0) return freshness;
                    int sample = Integer.compare(sampleSize(right), sampleSize(left));
                    return sample != 0 ? sample : left.code().compareTo(right.code());
                })
                .toList();
    }

    private static LocalDate freshest(Item item, Map<String, MainSleepInsight> byId,
            Map<String, MainSleepDay> durationsById, List<MainSleepInsight> records,
            List<MainSleepDay> currentDurationRows) {
        if ("A01".equals(item.code())) {
            List<LocalDate> days = currentDurationRows.isEmpty()
                    ? records.stream().map(MainSleepInsight::day).toList()
                    : currentDurationRows.stream().map(MainSleepDay::day).toList();
            return days.stream().max(LocalDate::compareTo).orElse(LocalDate.MIN);
        }
        if ("A03".equals(item.code())) {
            return item.sourceRefs().stream().map(durationsById::get).filter(java.util.Objects::nonNull)
                    .map(MainSleepDay::day).max(LocalDate::compareTo).orElse(LocalDate.MIN);
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
        if ("A03".equals(item.code())) {
            return ((Number) item.sample().get("baselineMainDays")).intValue()
                    + ((Number) item.sample().get("recentMainDays")).intValue();
        }
        if ("B03".equals(item.code())) {
            return ((Number) item.sample().get("baselineRatedMain")).intValue()
                    + ((Number) item.sample().get("recentRatedMain")).intValue();
        }
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
                List.of("COVERAGE_IS_NOT_SLEEP_DURATION"), POLICY, evidenceVersionDays(period, window.records()));
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

    private static String evidenceVersionDays(Period period, List<MainSleepDay> rows) {
        String source = period.start() + "|" + period.end() + "|" + period.timeZone() + "\n" + rows.stream()
                .sorted((a, b) -> a.sourceId().compareTo(b.sourceId()))
                .map(row -> row.sourceId() + "|" + row.day() + "|" + row.minutes())
                .reduce("", (a, b) -> a + "\n" + b);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<Map<String, Object>> a01Days(Period period, MainSleepWindow window) {
        Map<LocalDate, MainSleepDay> byDay = new java.util.HashMap<>();
        window.records().forEach(row -> byDay.put(row.day(), row));
        List<Map<String, Object>> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = period.start().plusDays(offset);
            days.add(Map.of("day", day, "status", byDay.containsKey(day) ? "RECORDED" : "NOT_RECORDED"));
        }
        return List.copyOf(days);
    }

    private static List<Map<String, Object>> a02Days(Period period, MainSleepInsightWindow window,
            SleepGoalWindow goalWindow) {
        Map<LocalDate, MainSleepInsight> byDay = new java.util.HashMap<>();
        window.records().forEach(row -> byDay.put(row.day(), row));
        List<Map<String, Object>> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = period.start().plusDays(offset);
            MainSleepInsight main = byDay.get(day);
            String status = main == null ? "NO_MAIN" : !goalWindow.goals().containsKey(day) ? "NO_GOAL"
                    : main.minutes() >= goalWindow.goals().get(day) ? "MET" : "MISSED";
            days.add(Map.of("day", day, "status", status));
        }
        return List.copyOf(days);
    }

    private static Map<String, Object> withDays(Map<String, Object> evidence, List<Map<String, Object>> days) {
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        result.put("days", days);
        return Map.copyOf(result);
    }

    private static String a02EvidenceVersion(Period period, List<MainSleepInsight> eligible,
            SleepGoalWindow goalWindow, List<Map<String, Object>> days) {
        String daySource = days.stream().map(day -> day.get("day") + "|" + day.get("status"))
                .reduce("", (left, right) -> left + "\n" + right);
        String source = period.start() + "|" + period.end() + "|" + period.timeZone() + daySource + "\n"
                + eligible.stream().sorted((left, right) -> left.id().compareTo(right.id()))
                        .map(row -> row.id() + "|" + row.day() + "|" + row.minutes() + "|" + row.feeling()
                                + "|" + row.confirmedAt() + "|" + goalWindow.goals().get(row.day()))
                        .reduce("", (left, right) -> left + "\n" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
