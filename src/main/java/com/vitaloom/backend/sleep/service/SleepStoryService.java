package com.vitaloom.backend.sleep.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SleepStoryService {
    public static final Set<String> SUPPORTED_CODES = Set.of("A01", "A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04");
    private static final String HEADLINE = "Nhìn lại giấc ngủ gần đây";
    private final ConsentStore consent;
    private final SleepInsightsStatisticsService insights;
    private final ObjectProvider<Provider> providers;

    public SleepStoryService(ConsentStore consent, SleepInsightsStatisticsService insights,
            ObjectProvider<Provider> providers) {
        this.consent = consent;
        this.insights = insights;
        this.providers = providers;
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
        Provider provider = providers.getIfAvailable();
        if (provider == null) return Response.empty("DISABLED");

        Consent before = consent.read(owner);
        if (!before.enabled()) return Response.empty("CONSENT_REQUIRED");

        Evidence original = evidence(owner, selectedZone);
        if (!original.eligible()) return Response.empty("INELIGIBLE");

        Story output;
        try {
            output = provider.generate(original);
        } catch (RuntimeException failure) {
            output = null;
        }

        Evidence current = evidence(owner, selectedZone);
        if (!before.equals(consent.read(owner))) return Response.empty("CONSENT_REQUIRED");
        if (!current.eligible()) return Response.empty("INELIGIBLE");
        if (!original.fingerprint().equals(current.fingerprint()) || !valid(output, current)) {
            Story fallback = preview(current);
            return new Response("FALLBACK", fallback.headline(), fallback.observations());
        }
        return new Response("STORY", output.headline(), List.copyOf(output.observations()));
    }

    public ExplanationResponse explanation(String owner, String code, String versionHint) {
        return explanation(owner, code, versionHint, null);
    }

    public ExplanationResponse explanation(String owner, String code, String versionHint, ZoneId selectedZone) {
        Provider provider = providers.getIfAvailable();
        if (provider == null) return ExplanationResponse.empty("DISABLED");
        Consent before = consent.read(owner);
        if (!before.enabled()) return ExplanationResponse.empty("CONSENT_REQUIRED");
        Evidence original = explanationEvidence(owner, code, selectedZone);
        if (original.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
        Candidate selected = original.candidates().getFirst();
        if (versionHint != null && !versionHint.isBlank() && !versionHint.equals(selected.fingerprint())) {
            return ExplanationResponse.empty("STALE");
        }
        Explanation output;
        try {
            output = provider.explain(original);
        } catch (RuntimeException failure) {
            output = null;
        }
        Evidence current = explanationEvidence(owner, code, selectedZone);
        if (!before.equals(consent.read(owner))) return ExplanationResponse.empty("CONSENT_REQUIRED");
        if (current.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
        if (!original.fingerprint().equals(current.fingerprint())) {
            return ExplanationResponse.empty("STALE");
        }
        Explanation fallback = explanationPreview(current);
        if (!fallback.equals(output) || wordCount(fallback.observation()) + wordCount(fallback.evidenceBasis())
                + wordCount(fallback.limitations()) > 150) {
            return ExplanationResponse.fallback(fallback);
        }
        return ExplanationResponse.generated(output);
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
    public record Response(String status, String headline, List<Observation> observations) {
        static Response empty(String status) {
            return new Response(status, null, null);
        }
    }
}
