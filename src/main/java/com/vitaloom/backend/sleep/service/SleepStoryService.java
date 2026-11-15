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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SleepStoryService {
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

    public Response story(String owner) {
        Provider provider = providers.getIfAvailable();
        if (provider == null) return Response.empty("DISABLED");

        Consent before = consent.read(owner);
        if (!before.enabled()) return Response.empty("CONSENT_REQUIRED");

        Evidence original = evidence(owner);
        if (!original.eligible()) return Response.empty("INELIGIBLE");

        Story output;
        try {
            output = provider.generate(original);
        } catch (RuntimeException failure) {
            output = null;
        }

        Evidence current = evidence(owner);
        if (!before.equals(consent.read(owner))) return Response.empty("CONSENT_REQUIRED");
        if (!current.eligible()) return Response.empty("INELIGIBLE");
        if (!original.equals(current) || !valid(output, current)) {
            Story fallback = preview(current);
            return new Response("FALLBACK", fallback.headline(), fallback.observations());
        }
        return new Response("STORY", output.headline(), List.copyOf(output.observations()));
    }

    public ExplanationResponse explanation(String owner, String code, String versionHint) {
        Provider provider = providers.getIfAvailable();
        if (provider == null) return ExplanationResponse.empty("DISABLED");
        Consent before = consent.read(owner);
        if (!before.enabled()) return ExplanationResponse.empty("CONSENT_REQUIRED");
        Evidence original = explanationEvidence(owner, code);
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
        Evidence current = explanationEvidence(owner, code);
        if (!before.equals(consent.read(owner))) return ExplanationResponse.empty("CONSENT_REQUIRED");
        if (current.candidates().isEmpty()) return ExplanationResponse.empty("INELIGIBLE");
        if (!original.equals(current)) {
            return ExplanationResponse.empty("STALE");
        }
        Explanation fallback = explanationPreview(current);
        if (!fallback.equals(output) || wordCount(fallback.observation()) + wordCount(fallback.evidenceBasis())
                + wordCount(fallback.limitations()) > 150) {
            return ExplanationResponse.fallback(fallback);
        }
        return ExplanationResponse.generated(output);
    }

    private Evidence explanationEvidence(String owner, String code) {
        var detail = insights.detail(owner, code);
        if (!"READY".equals(detail.status())) return new Evidence(detail.period(), List.of());
        return new Evidence(detail.period(), List.of(normalize(
                detail.code(), detail.policyVersion(), detail.evidenceVersion(), detail.sample(), detail.evidence())));
    }

    private Evidence evidence(String owner) {
        var published = insights.insights(owner);
        if (published.items().stream().anyMatch(item -> "UNVERIFIED_DATA".equals(item.status()))) {
            return new Evidence(published.period(), List.of());
        }
        return new Evidence(published.period(), published.items().stream()
                .filter(item -> "READY".equals(item.status()) && !item.locked())
                .filter(item -> "A01".equals(item.code()) || "B01".equals(item.code()))
                .map(SleepStoryService::normalize)
                .sorted(Comparator.comparing(Candidate::code)).toList());
    }

    private static Candidate normalize(Item item) {
        return normalize(item.code(), item.policyVersion(), item.evidenceVersion(), item.sample(), item.evidence());
    }

    private static Candidate normalize(String code, String policy, String fingerprint, Map<String, Object> sample,
            Map<String, Object> evidence) {
        if ("A01".equals(code)) {
            return new Candidate(code, policy, fingerprint, number(sample, "recordedMainDays"), 0, 0, 0, 0);
        }
        return new Candidate(code, policy, fingerprint, number(sample, "ratedMain"), number(sample, "mainWithoutFeeling"),
                count(evidence, "comfortable"), count(evidence, "normal"), count(evidence, "tired"));
    }

    private static int number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).intValue();
    }

    private static int count(Map<String, Object> evidence, String category) {
        var distribution = (Map<?, ?>) evidence.get(category);
        return ((Number) distribution.get("count")).intValue();
    }

    public static Story preview(Evidence evidence) {
        var observation = evidence.candidates().stream().filter(candidate -> !"A01".equals(candidate.code()))
                .findFirst().map(candidate -> observation(evidence.period(), candidate)).orElseThrow();
        return new Story(HEADLINE, List.of(observation));
    }

    private static Observation observation(Period period, Candidate candidate) {
        String window = "Từ %s đến %s (%s).".formatted(period.start(), period.end(), period.timeZone());
        if ("A01".equals(candidate.code())) {
            return new Observation(candidate.code(),
                    "Bạn có ghi nhận giấc ngủ chính trong %d ngày.".formatted(candidate.sample()),
                    window + " Đây là số ngày có bản ghi, không phải thời lượng ngủ.", candidate.fingerprint());
        }
        String text = "Bạn ghi nhận %d lần thoải mái, %d lần bình thường và %d lần mệt mỏi sau giấc ngủ chính."
                .formatted(candidate.comfortable(), candidate.normal(), candidate.tired());
        String context = window + " Có %d lần tự báo cáo cảm nhận và %d bản ghi thiếu cảm nhận."
                .formatted(candidate.sample(), candidate.missing());
        return new Observation(candidate.code(), text, context, candidate.fingerprint());
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
        if ("A01".equals(candidate.code())) {
            return new Explanation("Bạn đã ghi lại giấc ngủ chính trong %d ngày.".formatted(candidate.sample()),
                    "Từ %s đến %s, mỗi ngày có bản ghi giấc ngủ chính được tính một lần trong tổng số 7 ngày."
                            .formatted(evidence.period().start(), evidence.period().end()),
                    "Số ngày có bản ghi không phản ánh thời lượng hay chất lượng giấc ngủ.", candidate.fingerprint());
        }
        return new Explanation(
                "Đây là các cảm nhận bạn tự ghi lại sau giấc ngủ chính.",
                "Trong kỳ %s đến %s, có %d lần tự báo cáo: %d thoải mái, %d bình thường và %d mệt mỏi."
                        .formatted(evidence.period().start(), evidence.period().end(), candidate.sample(),
                                candidate.comfortable(), candidate.normal(), candidate.tired()),
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
    public record Evidence(Period period, List<Candidate> candidates) {
        public Evidence {
            candidates = List.copyOf(candidates);
        }

        boolean eligible() {
            return candidates.stream().anyMatch(candidate -> !"A01".equals(candidate.code()));
        }
    }
    public record Candidate(String code, String policyVersion, String fingerprint, int sample, int missing,
            int comfortable, int normal, int tired) {
        public Candidate {
            Objects.requireNonNull(fingerprint);
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
