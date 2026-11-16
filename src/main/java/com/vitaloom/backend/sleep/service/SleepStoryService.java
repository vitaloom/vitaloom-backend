package com.vitaloom.backend.sleep.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SleepStoryService {
    public static final Set<String> SUPPORTED_CODES = Set.of("A01", "A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04");
    private static final String HEADLINE = "Nhìn lại giấc ngủ gần đây";
    private final ConsentStore consent;
    private final SleepInsightsStatisticsService insights;
    private final ObjectProvider<Provider> providers;
    private final SleepAiCache cache;
    private final Clock clock;
    private final Map<String, StorySuccess> storySuccesses = new ConcurrentHashMap<>();
    private final Map<ExplanationKey, ExplanationSuccess> explanationSuccesses = new ConcurrentHashMap<>();
    private final Map<FlightKey, CompletableFuture<Response>> storyFlights = new ConcurrentHashMap<>();
    private final Map<FlightKey, CompletableFuture<ExplanationResponse>> explanationFlights = new ConcurrentHashMap<>();
    private static final Duration STORY_COOLDOWN = Duration.ofMinutes(15);
    private static final Duration EXPLANATION_COOLDOWN = Duration.ofMinutes(5);
    private static final String COOLDOWN_NOTICE = "Bạn có thể tạo câu chuyện mới sau thời gian chờ 15 phút.";
    private static final String CONTENT_POLICY = "sleep-ai-policy-v1";
    private static final String STORY_PROMPT = "story-template-v1";
    private static final String EXPLANATION_PROMPT = "explanation-template-v1";
    private final long storyBudgetNanos;
    private final long explanationBudgetNanos;
    private final long revalidationReserveNanos;
    private final LongSupplier ticker;
    private static final ThreadPoolExecutor PROVIDER_WORK = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "sleep-ai-provider"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor READ_WORK = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "sleep-ai-read"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor WRITE_WORK = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "sleep-ai-write"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    public SleepStoryService(ConsentStore consent, SleepInsightsStatisticsService insights,
            ObjectProvider<Provider> providers) {
        this(consent, insights, providers, SleepAiCache.bypass());
    }

    public SleepStoryService(ConsentStore consent, SleepInsightsStatisticsService insights,
            ObjectProvider<Provider> providers, SleepAiCache cache) {
        this(consent, insights, providers, cache, Clock.systemUTC());
    }

    @Autowired
    public SleepStoryService(ConsentStore consent, SleepInsightsStatisticsService insights,
            ObjectProvider<Provider> providers, SleepAiCache cache, Clock clock) {
        this(consent, insights, providers, cache, clock, TimeUnit.SECONDS.toNanos(20),
                TimeUnit.SECONDS.toNanos(10), TimeUnit.SECONDS.toNanos(1), System::nanoTime);
    }

    public SleepStoryService(ConsentStore consent, SleepInsightsStatisticsService insights, ObjectProvider<Provider> providers,
            SleepAiCache cache, Clock clock, long storyBudgetNanos, long explanationBudgetNanos,
            long revalidationReserveNanos, LongSupplier ticker) {
        this.consent = consent;
        this.insights = insights;
        this.providers = providers;
        this.cache = cache;
        this.clock = clock;
        this.storyBudgetNanos = storyBudgetNanos;
        this.explanationBudgetNanos = explanationBudgetNanos;
        this.revalidationReserveNanos = revalidationReserveNanos;
        this.ticker = ticker;
    }

    public Preference preference(String owner) {
        return new Preference(consent.read(owner).enabled(), providers.getIfAvailable() != null);
    }

    public Preference updatePreference(String owner, boolean enabled) {
        boolean available = providers.getIfAvailable() != null;
        if (enabled && !available) {
            throw new SleepFailure(409, "SLEEP_STORY_DISABLED", "Sleep Story is disabled.");
        }
        consent.write(owner, enabled);
        return new Preference(enabled, available);
    }

    public Response story(String owner) { return story(owner, null); }

    public Response story(String owner, ZoneId selectedZone) {
        long deadline = ticker.getAsLong() + storyBudgetNanos;
        Provider provider = providers.getIfAvailable();
        if (provider == null) return Response.empty("DISABLED");

        Consent before;
        try { before = timedRead(() -> consent.read(owner), deadline, ticker); }
        catch (DeadlineExceeded expired) { return Response.empty("CONSENT_REQUIRED"); }
        if (!before.enabled()) return Response.empty("CONSENT_REQUIRED");

        Evidence original;
        try { original = timedRead(() -> evidence(owner, selectedZone), deadline, ticker); }
        catch (DeadlineExceeded expired) { return Response.empty("INELIGIBLE"); }
        if (!original.eligible()) return Response.empty("INELIGIBLE");

        String key = cacheKey("story", owner, original, STORY_PROMPT);
        return singleFlight(storyFlights, new FlightKey(key, before), deadline,
                () -> generateStory(owner, selectedZone, provider, before, original, key, deadline), result -> {
                    Evidence current;
                    try {
                        current = timedRead(() -> evidence(owner, selectedZone), deadline, ticker);
                        if (!before.equals(timedRead(() -> consent.read(owner), deadline, ticker))) {
                            return Response.empty("CONSENT_REQUIRED");
                        }
                    } catch (DeadlineExceeded expired) { return Response.empty("CONSENT_REQUIRED"); }
                    if (!current.eligible()) return Response.empty("INELIGIBLE");
                    if (result == null || !key.equals(cacheKey("story", owner, current, STORY_PROMPT))
                            || (result.observations() != null
                                    && !valid(new Story(result.headline(), result.observations()), current))) {
                        return storyFallback(current, false);
                    }
                    return mayDispatch(deadline) ? result : Response.empty("CONSENT_REQUIRED");
                });
    }

    private Response generateStory(String owner, ZoneId selectedZone, Provider provider, Consent before,
            Evidence original, String key, long deadline) {
        Story output = null;
        boolean cached = false;
        boolean coolingDown = false;
        try {
            output = timedRead(() -> cache.readStory(key), deadline, ticker);
            cached = output != null;
            if (!cached) {
                StorySuccess previous = storySuccesses.get(owner);
                coolingDown = previous != null && !original.period().end().isAfter(previous.day())
                        && clock.instant().isBefore(previous.at().plus(STORY_COOLDOWN));
                if (!coolingDown) {
                    long providerDeadline = deadline - revalidationReserveNanos;
                    output = providerCall(() -> provider.generate(original), providerDeadline,
                            () -> key.equals(cacheKey("story", owner,
                                    timedRead(() -> evidence(owner, selectedZone), providerDeadline, ticker), STORY_PROMPT)),
                            () -> before.equals(consent.read(owner)));
                }
            }
        } catch (RuntimeException failure) {
            output = null;
        }

        Evidence current;
        try { current = timedRead(() -> evidence(owner, selectedZone), deadline, ticker); }
        catch (DeadlineExceeded expired) { return Response.empty("INELIGIBLE"); }
        try { if (!before.equals(timedRead(() -> consent.read(owner), deadline, ticker))) return Response.empty("CONSENT_REQUIRED"); }
        catch (DeadlineExceeded expired) { return Response.empty("CONSENT_REQUIRED"); }
        if (!current.eligible()) return Response.empty("INELIGIBLE");
        if (!key.equals(cacheKey("story", owner, current, STORY_PROMPT)) || !valid(output, current)) {
            return storyFallback(current, coolingDown);
        }
        Response generated = new Response("STORY", output.headline(), List.copyOf(output.observations()));
        if (!cached) {
            Story publish = output;
            if (!timedWrite(() -> cache.writeStory(key, publish, () -> mayDispatch(deadline)), deadline)) {
                return storyFallback(current, false);
            }
            StorySuccess published = new StorySuccess(clock.instant(), current.period().end());
            return recordPublication(storySuccesses, owner, published, deadline) ? generated : storyFallback(current, false);
        }
        return mayDispatch(deadline) ? generated : storyFallback(current, false);
    }

    public ExplanationResponse explanation(String owner, String code, String versionHint) {
        return explanation(owner, code, versionHint, null);
    }

    public ExplanationResponse explanation(String owner, String code, String versionHint, ZoneId selectedZone) {
        long deadline = ticker.getAsLong() + explanationBudgetNanos;
        Provider provider = providers.getIfAvailable();
        if (provider == null) return ExplanationResponse.empty("DISABLED");
        Consent before;
        try { before = timedRead(() -> consent.read(owner), deadline, ticker); }
        catch (DeadlineExceeded expired) { return ExplanationResponse.empty("CONSENT_REQUIRED"); }
        if (!before.enabled()) return ExplanationResponse.empty("CONSENT_REQUIRED");
        Evidence original;
        try { original = timedRead(() -> explanationEvidence(owner, code, selectedZone), deadline, ticker); }
        catch (DeadlineExceeded expired) { return ExplanationResponse.empty("INELIGIBLE"); }
        if (original.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
        Candidate selected = original.candidates().getFirst();
        if (versionHint != null && !versionHint.isBlank() && !versionHint.equals(selected.fingerprint())) {
            return ExplanationResponse.empty("STALE");
        }
        String key = cacheKey("explanation", owner, original, EXPLANATION_PROMPT);
        return singleFlight(explanationFlights, new FlightKey(key, before), deadline,
                () -> generateExplanation(owner, code, selectedZone, provider, before, original, key, deadline), result -> {
                    Evidence current;
                    try {
                        current = timedRead(() -> explanationEvidence(owner, code, selectedZone), deadline, ticker);
                        if (!before.equals(timedRead(() -> consent.read(owner), deadline, ticker))) {
                            return ExplanationResponse.empty("CONSENT_REQUIRED");
                        }
                    } catch (DeadlineExceeded expired) { return ExplanationResponse.empty("CONSENT_REQUIRED"); }
                    if (current.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
                    if (!key.equals(cacheKey("explanation", owner, current, EXPLANATION_PROMPT))) {
                        return ExplanationResponse.empty("STALE");
                    }
                    if (!mayDispatch(deadline)) return ExplanationResponse.empty("CONSENT_REQUIRED");
                    Explanation fallback = explanationPreview(current);
                    if (result == null || (result.evidenceFingerprint() != null
                            && !fallback.equals(new Explanation(result.observation(), result.evidenceBasis(),
                                    result.limitations(), result.evidenceFingerprint())))) {
                        return ExplanationResponse.fallback(fallback);
                    }
                    return result;
                });
    }

    private ExplanationResponse generateExplanation(String owner, String code, ZoneId selectedZone, Provider provider,
            Consent before, Evidence original, String key, long deadline) {
        Explanation output = null;
        boolean cached = false;
        ExplanationKey cooldownKey = new ExplanationKey(owner, code);
        try {
            output = timedRead(() -> cache.readExplanation(key), deadline, ticker);
            cached = output != null;
            if (!cached) {
                ExplanationSuccess previous = explanationSuccesses.get(cooldownKey);
                if (previous == null || !clock.instant().isBefore(previous.at().plus(EXPLANATION_COOLDOWN))) {
                    long providerDeadline = deadline - revalidationReserveNanos;
                    output = providerCall(() -> provider.explain(original), providerDeadline,
                            () -> key.equals(cacheKey("explanation", owner,
                                    timedRead(() -> explanationEvidence(owner, code, selectedZone), providerDeadline, ticker),
                                    EXPLANATION_PROMPT)),
                            () -> before.equals(consent.read(owner)));
                }
            }
        } catch (RuntimeException failure) {
            output = null;
        }
        Evidence current;
        try { current = timedRead(() -> explanationEvidence(owner, code, selectedZone), deadline, ticker); }
        catch (DeadlineExceeded expired) { return ExplanationResponse.empty("INELIGIBLE"); }
        try { if (!before.equals(timedRead(() -> consent.read(owner), deadline, ticker))) return ExplanationResponse.empty("CONSENT_REQUIRED"); }
        catch (DeadlineExceeded expired) { return ExplanationResponse.empty("CONSENT_REQUIRED"); }
        if (current.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
        if (!key.equals(cacheKey("explanation", owner, current, EXPLANATION_PROMPT))) {
            return ExplanationResponse.empty("STALE");
        }
        Explanation fallback = explanationPreview(current);
        if (!fallback.equals(output) || wordCount(fallback.observation()) + wordCount(fallback.evidenceBasis())
                + wordCount(fallback.limitations()) > 150) {
            return ExplanationResponse.fallback(fallback);
        }
        ExplanationResponse generated = ExplanationResponse.generated(output);
        if (!cached) {
            Explanation publish = output;
            if (!timedWrite(() -> cache.writeExplanation(key, publish, () -> mayDispatch(deadline)), deadline)) {
                return ExplanationResponse.fallback(fallback);
            }
            var published = new ExplanationSuccess(clock.instant());
            return recordPublication(explanationSuccesses, cooldownKey, published, deadline)
                    ? generated : ExplanationResponse.fallback(fallback);
        }
        return mayDispatch(deadline) ? generated : ExplanationResponse.fallback(fallback);
    }

    private <T> T singleFlight(Map<FlightKey, CompletableFuture<T>> flights, FlightKey key, long deadline,
            Supplier<T> generate, java.util.function.Function<T, T> revalidate) {
        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<T> existing = flights.putIfAbsent(key, created);
        if (existing == null) {
            try {
                T result = generate.get();
                created.complete(result);
                return result;
            } catch (RuntimeException | Error failure) {
                created.completeExceptionally(failure);
                throw failure;
            } finally {
                flights.remove(key, created);
            }
        }
        T result = null;
        try {
            long remaining = deadline - revalidationReserveNanos - ticker.getAsLong();
            if (remaining > 0) result = existing.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unavailable) {
            // A waiter owns its deadline, but must not cancel work shared by other requests.
        }
        return revalidate.apply(result);
    }

    private record FlightKey(String identity, Consent consent) {}

    private <K, V> boolean recordPublication(Map<K, V> successes, K key, V published, long deadline) {
        // Decide publication and cooldown together so an expired call cannot replace a concurrent success.
        return successes.compute(key, (ignored, previous) -> mayDispatch(deadline) ? published : previous) == published;
    }

    private static Response storyFallback(Evidence evidence, boolean coolingDown) {
        Story fallback = preview(evidence);
        return new Response("FALLBACK", fallback.headline(), fallback.observations(), coolingDown ? COOLDOWN_NOTICE : null);
    }

    private static String cacheKey(String kind, String owner, Evidence evidence, String prompt) {
        return "sleep:ai:" + kind + ":" + SleepAiEvidenceIdentity.hash(
                List.of(owner, evidence.fingerprint(), CONTENT_POLICY, prompt));
    }

    private Evidence explanationEvidence(String owner, String code, ZoneId selectedZone) {
        if (!SUPPORTED_CODES.contains(code)) return new Evidence(null, null, List.of());
        var detail = selectedZone == null ? insights.detail(owner, code) : insights.detail(owner, code, selectedZone);
        if (!"READY".equals(detail.status())) return new Evidence(detail.period(), zoneId(selectedZone), List.of());
        return new Evidence(detail.period(), zoneId(selectedZone), List.of(new Candidate(
                detail.code(), detail.policyVersion(), detail.evidenceVersion(), detail.period(), detail.sample(), detail.evidence())));
    }

    private Evidence evidence(String owner, ZoneId selectedZone) {
        var published = insights.fullInsights(owner, selectedZone);
        return new Evidence(published.period(), zoneId(selectedZone), published.items().stream()
                .filter(item -> "READY".equals(item.status()) && !item.locked())
                .filter(item -> SUPPORTED_CODES.contains(item.code()))
                .map(item -> new Candidate(item.code(), item.policyVersion(), item.evidenceVersion(),
                        candidatePeriod(published.period(), item), item.sample(), item.evidence()))
                .sorted(Comparator.comparing(Candidate::code)).toList());
    }

    private static Period candidatePeriod(Period current, Item item) {
        if (Set.of("A03", "B03").contains(item.code())) {
            return (Period) ((Map<?, ?>) item.evidence().get("recent")).get("period");
        }
        return current;
    }

    private static String zoneId(ZoneId zone) { return zone == null ? null : zone.getId(); }

    private static int number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).intValue();
    }

    private static int count(Map<String, Object> evidence, String category) {
        return ((Number) ((Map<?, ?>) evidence.get(category)).get("count")).intValue();
    }

    public static Story preview(Evidence evidence) {
        var observation = evidence.candidates().stream().filter(candidate -> !"A01".equals(candidate.code()))
                .findFirst().map(candidate -> observation(evidence.period(), candidate)).orElseThrow();
        return new Story(HEADLINE, List.of(observation));
    }

    private static Observation observation(Period period, Candidate candidate) {
        period = candidate.period();
        String window = "Từ %s đến %s (%s).".formatted(period.start(), period.end(), period.timeZone());
        if ("A01".equals(candidate.code())) {
            return new Observation(candidate.code(),
                    "Bạn có ghi nhận giấc ngủ chính trong %d ngày.".formatted(number(candidate.sample(), "recordedMainDays")),
                    window + " Đây là số ngày có bản ghi, không phải thời lượng ngủ.", candidate.fingerprint());
        }
        if (!"B01".equals(candidate.code())) {
            return catalogObservation(window, candidate);
        }
        String text = "Bạn ghi nhận %d lần thoải mái, %d lần bình thường và %d lần mệt mỏi sau giấc ngủ chính."
                .formatted(count(candidate.evidence(), "comfortable"), count(candidate.evidence(), "normal"), count(candidate.evidence(), "tired"));
        String context = window + " Có %d lần tự báo cáo cảm nhận và %d bản ghi thiếu cảm nhận."
                .formatted(number(candidate.sample(), "ratedMain"), number(candidate.sample(), "mainWithoutFeeling"));
        return new Observation(candidate.code(), text, context, candidate.fingerprint());
    }

    private static Observation catalogObservation(String window, Candidate candidate) {
        Map<String, Object> values = candidate.evidence();
        Map<String, Object> sample = candidate.sample();
        String text;
        String context = window + " Dữ liệu tự ghi nhận, chỉ mang tính mô tả.";
        switch (candidate.code()) {
            case "A02" -> text = "Bạn đạt mục tiêu ngủ trong %s trên %s ngày có giấc ngủ chính và mục tiêu."
                    .formatted(values.get("metGoalDays"), sample.get("eligibleMainDays"));
            case "A03" -> {
                var baseline = (Map<?, ?>) values.get("baseline");
                var recent = (Map<?, ?>) values.get("recent");
                text = "Thời lượng trung bình là %s phút ở kỳ trước và %s phút ở kỳ gần đây, chênh lệch %s phút."
                        .formatted(baseline.get("meanMinutes"), recent.get("meanMinutes"), values.get("deltaMinutes"));
                context = trendContext(candidate, baseline, sample.get("baselineMainDays"), sample.get("recentMainDays"));
            }
            case "B03" -> {
                var baseline = (Map<?, ?>) values.get("baseline");
                var delta = (Map<?, ?>) values.get("deltaPercentagePoints");
                text = "Tỷ lệ thoải mái, bình thường và mệt mỏi thay đổi lần lượt %s, %s và %s điểm phần trăm."
                        .formatted(delta.get("comfortable"), delta.get("normal"), delta.get("tired"));
                context = trendContext(candidate, baseline, sample.get("baselineRatedMain"), sample.get("recentRatedMain"))
                        + " Thiếu cảm nhận: %s và %s bản ghi tương ứng."
                                .formatted(sample.get("baselineMainWithoutFeeling"), sample.get("recentMainWithoutFeeling"));
            }
            case "A04" -> {
                var bedtime = (Map<?, ?>) values.get("bedtime");
                var wake = (Map<?, ?>) values.get("wake");
                text = "Khoảng dao động giờ đi ngủ là %s phút và giờ thức dậy là %s phút."
                        .formatted(bedtime.get("spanMinutes"), wake.get("spanMinutes"));
                context = window + " Có %s ngày ghi nhận giờ trong múi giờ %s; chỉ mô tả khoảng dao động."
                        .formatted(sample.get("eligibleRangeDays"), values.get("timeZone"));
            }
            case "A05", "B02" -> {
                boolean weekday = "A05".equals(candidate.code());
                var first = (Map<?, ?>) values.get(weekday ? "weekday" : "comfortable");
                var second = (Map<?, ?>) values.get(weekday ? "weekend" : "tired");
                text = "Thời lượng trung bình của nhóm %s là %s phút và nhóm %s là %s phút, chênh lệch %s phút."
                        .formatted(weekday ? "ngày thường" : "thoải mái", first.get("meanMinutes"),
                                weekday ? "cuối tuần" : "mệt mỏi", second.get("meanMinutes"), values.get("deltaMinutes"));
                context = window + " Hai nhóm có %s và %s ngày tương ứng; chỉ mô tả dữ liệu tự ghi nhận."
                        .formatted(first.get("days"), second.get("days"));
            }
            case "B04" -> text = "Bạn ghi nhận mệt mỏi dù đạt mục tiêu ngủ trong %s trên %s lần có cảm nhận."
                    .formatted(values.get("exceptionCount"), sample.get("ratedMain"));
            default -> throw new IllegalArgumentException("Unsupported insight code");
        }
        return new Observation(candidate.code(), text, context, candidate.fingerprint());
    }

    private static String trendContext(Candidate candidate, Map<?, ?> baseline, Object before, Object recent) {
        Period previous = (Period) baseline.get("period");
        return "Kỳ trước %s đến %s: %s mẫu; kỳ gần đây %s đến %s: %s mẫu (%s). Chỉ mô tả, không kết luận sức khỏe."
                .formatted(previous.start(), previous.end(), before, candidate.period().start(), candidate.period().end(),
                        recent, candidate.period().timeZone());
    }

    private static boolean valid(Story story, Evidence evidence) {
        if (story == null || !HEADLINE.equals(story.headline()) || story.observations() == null
                || story.observations().isEmpty() || story.observations().size() > 2) return false;

        // Check complete approved sentences so a correct number cannot validate an unsupported claim.
        var approved = evidence.candidates().stream().map(candidate -> observation(evidence.period(), candidate)).toList();
        var codes = new HashSet<String>();
        int words = wordCount(story.headline());
        for (Observation observation : story.observations()) {
            if (observation == null || approved.stream().noneMatch(observation::equals)
                    || !codes.add(observation.insightCode())) return false;
            words += wordCount(observation.text()) + wordCount(observation.dataContext());
        }
        return words <= 120 && codes.stream().anyMatch(code -> !"A01".equals(code));
    }

    public static Explanation explanationPreview(Evidence evidence) {
        Candidate candidate = evidence.candidates().get(0);
        if (!Set.of("A01", "B01").contains(candidate.code())) {
            Observation observation = observation(candidate.period(), candidate);
            return new Explanation(observation.text(), observation.dataContext(),
                    "Dữ liệu tự ghi nhận chỉ mang tính mô tả, không phải kết luận sức khỏe.", candidate.fingerprint());
        }
        if ("A01".equals(candidate.code())) {
            return new Explanation("Bạn đã ghi lại giấc ngủ chính trong %d ngày.".formatted(number(candidate.sample(), "recordedMainDays")),
                    "Từ %s đến %s, mỗi ngày có bản ghi giấc ngủ chính được tính một lần trong tổng số 7 ngày."
                            .formatted(evidence.period().start(), evidence.period().end()),
                    "Số ngày có bản ghi không phản ánh thời lượng hay chất lượng giấc ngủ.", candidate.fingerprint());
        }
        return new Explanation(
                "Đây là các cảm nhận bạn tự ghi lại sau giấc ngủ chính.",
                "Trong kỳ %s đến %s, có %d lần tự báo cáo: %d thoải mái, %d bình thường và %d mệt mỏi."
                        .formatted(evidence.period().start(), evidence.period().end(), number(candidate.sample(), "ratedMain"),
                                count(candidate.evidence(), "comfortable"), count(candidate.evidence(), "normal"), count(candidate.evidence(), "tired")),
                "Đây là mô tả từ dữ liệu tự ghi nhận, không phải kết luận sức khỏe.", candidate.fingerprint());
    }

    private static int wordCount(String text) {
        return text.strip().split("\\s+").length;
    }

    public interface ConsentStore {
        Consent read(String owner);
        void write(String owner, boolean enabled);
    }

    public interface Provider {
        Story generate(Evidence evidence);
        default Explanation explain(Evidence evidence) { return null; }
    }

    private static <T> T timedRead(Supplier<T> operation, long deadline, LongSupplier ticker) {
        long remaining = deadline - ticker.getAsLong();
        if (remaining <= 0) throw new DeadlineExceeded();
        Future<T> future = null;
        try {
            future = READ_WORK.submit(operation::get);
            T result = future.get(deadline - ticker.getAsLong(), TimeUnit.NANOSECONDS);
            if (deadline - ticker.getAsLong() <= 0) throw new DeadlineExceeded();
            return result;
        } catch (TimeoutException timeout) {
            if (future != null) future.cancel(true);
            throw new DeadlineExceeded();
        } catch (InterruptedException interrupted) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DeadlineExceeded();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (RuntimeException rejected) {
            throw new DeadlineExceeded();
        }
    }

    private boolean mayDispatch(long deadline) {
        return !Thread.currentThread().isInterrupted() && deadline - ticker.getAsLong() > 0;
    }

    private boolean timedWrite(Runnable operation, long deadline) {
        if (!mayDispatch(deadline)) return false;
        Future<?> future = null;
        try {
            future = WRITE_WORK.submit(() -> {
                if (mayDispatch(deadline)) operation.run();
            });
            future.get(deadline - ticker.getAsLong(), TimeUnit.NANOSECONDS);
            return mayDispatch(deadline);
        } catch (TimeoutException timeout) {
            if (future != null) future.cancel(true);
            return false;
        } catch (InterruptedException interrupted) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException failed) {
            return false;
        }
    }

    private static final class DeadlineExceeded extends RuntimeException {}

    private <T> T providerCall(Supplier<T> operation, long providerDeadline,
            BooleanSupplier retryEvidenceCurrent, BooleanSupplier retryConsentCurrent) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!mayDispatch(providerDeadline)) return null;
            Future<T> future = null;
            boolean retry = attempt == 1;
            try {
                future = PROVIDER_WORK.submit(() -> {
                    if (!mayDispatch(providerDeadline) || (retry && !retryConsentCurrent.getAsBoolean())) return null;
                    return mayDispatch(providerDeadline) ? operation.get() : null;
                });
                T result = future.get(providerDeadline - ticker.getAsLong(), TimeUnit.NANOSECONDS);
                return mayDispatch(providerDeadline) ? result : null;
            } catch (TimeoutException timeout) {
                if (future != null) future.cancel(true);
                return null;
            } catch (InterruptedException interrupted) {
                if (future != null) future.cancel(true);
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (!(cause instanceof TransientProviderException) || attempt == 1) return null;
                try {
                    if (!mayDispatch(providerDeadline) || !retryEvidenceCurrent.getAsBoolean()) return null;
                } catch (RuntimeException unavailable) {
                    return null;
                }
            } catch (RuntimeException rejected) {
                return null;
            }
        }
        return null;
    }

    public static final class TransientProviderException extends RuntimeException {
        public TransientProviderException(String message) { super(message); }
    }

    public record Consent(boolean enabled, String revision) {}
    public record Preference(boolean enabled, boolean available) {}
    public record Evidence(Period period, String selectedZone, List<Candidate> candidates) {
        public Evidence {
            candidates = candidates.stream().sorted(Comparator.comparing(Candidate::code)).toList();
        }

        public String fingerprint() {
            return SleepAiEvidenceIdentity.hash(java.util.Arrays.asList("sleep-ai-evidence-v1", period, selectedZone,
                    candidates.stream().map(Candidate::identity).toList()));
        }

        boolean eligible() {
            return candidates.stream().anyMatch(candidate -> !"A01".equals(candidate.code()));
        }
    }
    public record Candidate(String code, String policyVersion, String fingerprint, Period period,
            Map<String, Object> sample, Map<String, Object> evidence) {
        public Candidate {
            Objects.requireNonNull(fingerprint);
            sample = SleepAiEvidenceIdentity.snapshot(sample);
            evidence = SleepAiEvidenceIdentity.snapshot(evidence);
        }

        private Object identity() {
            return List.of(code, policyVersion, fingerprint, period, sample, evidence);
        }
    }
    public record Story(String headline, List<Observation> observations) {}
    public record Observation(String insightCode, String text, String dataContext, String evidenceFingerprint) {}
    public record Explanation(String observation, String evidenceBasis, String limitations, String evidenceFingerprint) {}
    private record StorySuccess(Instant at, LocalDate day) {}
    private record ExplanationKey(String owner, String code) {}
    private record ExplanationSuccess(Instant at) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExplanationResponse(String status, String observation, String evidenceBasis, String limitations,
            String evidenceFingerprint) {
        static ExplanationResponse empty(String status) { return new ExplanationResponse(status, null, null, null, null); }
        static ExplanationResponse generated(Explanation value) {
            return new ExplanationResponse("EXPLANATION", value.observation(), value.evidenceBasis(), value.limitations(),
                    value.evidenceFingerprint());
        }
        static ExplanationResponse fallback(Explanation value) {
            return new ExplanationResponse("FALLBACK", value.observation(), value.evidenceBasis(), value.limitations(),
                    value.evidenceFingerprint());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String status, String headline, List<Observation> observations, String notice) {
        public Response(String status, String headline, List<Observation> observations) {
            this(status, headline, observations, null);
        }
        static Response empty(String status) {
            return new Response(status, null, null);
        }
    }
}
