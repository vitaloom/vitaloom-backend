package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.service.DeterministicSleepStoryProvider;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.Explanation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Observation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Insights;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Detail;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SleepStoryServiceTest {
    private final ConsentStore consent = mock(ConsentStore.class);
    private final SleepInsightsStatisticsService insights = mock(SleepInsightsStatisticsService.class);
    private final Provider provider = mock(Provider.class);
    private final SleepStoryService service = new SleepStoryService(consent, insights, providerLookup());

    @SuppressWarnings("unchecked")
    private ObjectProvider<Provider> providerLookup() {
        ObjectProvider<Provider> lookup = mock(ObjectProvider.class);
        when(lookup.getIfAvailable()).thenReturn(provider);
        return lookup;
    }

    @Test
    void offAndA01OnlyNeverCallProvider() {
        when(consent.read("owner")).thenReturn(new Consent(false, ""));
        assertThat(service.story("owner").status()).isEqualTo("CONSENT_REQUIRED");
        verifyNoInteractions(insights);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("A01", "a")));
        assertThat(service.story("owner").status()).isEqualTo("INELIGIBLE");
        verifyNoInteractions(provider);
    }

    @Test
    void sendsTheFullReadyCatalogIndependentlyOfRecentHighlightCap() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(
                item("A01", "a"), item("A02", "a2"), item("A03", "a3"), item("A04", "a4"),
                item("A05", "a5"), item("B01", "b1"), item("B02", "b2"), item("B03", "b3"), item("B04", "b4")));
        when(provider.generate(any())).thenAnswer(invocation -> {
            var evidence = (SleepStoryService.Evidence) invocation.getArgument(0);
            assertThat(evidence.candidates()).extracting(SleepStoryService.Candidate::code)
                    .containsExactly("A01", "A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04");
            return SleepStoryService.preview(evidence);
        });
        assertThat(service.story("owner").status()).isEqualTo("STORY");
    }

    @Test
    void stableFingerprintIncludesNormalizedNonSelectedCandidateEvidence() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        var first = item("B02", "same");
        var changed = new Item(first.code(), first.category(), first.status(), first.title(), first.highlight(), first.locked(),
                first.summary(), first.sample(), Map.of("comfortable", Map.of("days", 3, "meanMinutes", 421),
                        "tired", Map.of("days", 3, "meanMinutes", 400), "deltaMinutes", 21),
                first.sourceRefs(), first.limitations(), first.policyVersion(), first.evidenceVersion());
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b"), first),
                snapshot(item("B01", "b"), changed));
        when(provider.generate(any())).thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        assertThat(service.story("owner").status()).isEqualTo("FALLBACK");
        verify(provider).generate(any());
    }

    @Test
    void identicalCanonicalEvidenceFingerprintIsStableAcrossMapOrder() {
        var period = snapshot(item("B01", "b")).period();
        var left = new SleepStoryService.Evidence(period, null, List.of(new SleepStoryService.Candidate("B01", "p", "v",
                period, Map.of("ratedMain", 3, "mainWithoutFeeling", 1),
                Map.of("comfortable", Map.of("count", 1, "denominator", 3)))));
        var right = new SleepStoryService.Evidence(period, null, List.of(new SleepStoryService.Candidate("B01", "p", "v",
                period, Map.of("mainWithoutFeeling", 1.0, "ratedMain", 3L),
                Map.of("comfortable", Map.of("denominator", 3L, "count", 1L)))));
        assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
    }

    @Test
    void rejectsFabricatedClaimAndFallsBackToCurrentEvidence() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "current")));
        when(provider.generate(any())).thenReturn(new Story("Ngủ ngon hơn 99%",
                List.of(new Observation("B01", "99 đêm", "99 ngày", "current"))));
        var result = service.story("owner");
        assertThat(result.status()).isEqualTo("FALLBACK");
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.evidenceFingerprint()).isEqualTo("current");
            assertThat(observation.text()).doesNotContain("99");
        });
    }

    @Test
    void discardsStoryWhenAnyPublishedCandidateChanges() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(
                snapshot(item("A01", "before"), item("B01", "b")),
                snapshot(item("A01", "after"), item("B01", "b")));
        when(provider.generate(any())).thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        assertThat(service.story("owner").status()).isEqualTo("FALLBACK");
        verify(provider, times(1)).generate(any());
    }

    @Test
    void consentChangedDuringGenerationSuppressesAllOutput() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"), new Consent(true, "re-enabled"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "current")));
        when(provider.generate(any())).thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        var response = service.story("owner");
        assertThat(response.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(response.observations()).isNull();
    }

    @Test
    void explanationIsConsentGatedAndUsesCurrentEvidence() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.detail("owner", "B01")).thenReturn(detail("current"));
        when(provider.explain(any())).thenAnswer(invocation -> SleepStoryService.explanationPreview(invocation.getArgument(0)));
        var response = service.explanation("owner", "B01", "current");
        assertThat(response.status()).isEqualTo("EXPLANATION");
        assertThat(response.evidenceFingerprint()).isEqualTo("current");
        verify(provider).explain(any());
    }

    @Test
    void explanationRejectsChangedNormalizedEvidenceEvenWhenDetailVersionWasReused() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        var changedItem = item("A02", "same-version");
        var changed = new Detail(changedItem.code(), changedItem.category(), changedItem.status(), snapshotPeriod(),
                changedItem.title(), changedItem.summary(), Map.of("eligibleMainDays", 5), changedItem.evidence(),
                changedItem.sourceRefs(), changedItem.limitations(), changedItem.policyVersion(), changedItem.evidenceVersion());
        var before = detail("A02", "same-version");
        when(insights.detail("owner", "A02")).thenReturn(before, changed);
        when(provider.explain(any())).thenAnswer(invocation -> SleepStoryService.explanationPreview(invocation.getArgument(0)));
        var response = service.explanation("owner", "A02", "same-version");
        assertThat(response.status()).isEqualTo("STALE");
        assertThat(response.observation()).isNull();
    }

    @Test
    void explanationDoesNotCallProviderWhenConsentIsOffOrVersionIsStale() {
        when(consent.read("owner")).thenReturn(new Consent(false, "off"));
        assertThat(service.explanation("owner", "B01", "current").status()).isEqualTo("CONSENT_REQUIRED");
        verifyNoInteractions(insights, provider);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.detail("owner", "B01")).thenReturn(detail("current"));
        assertThat(service.explanation("owner", "B01", "old").status()).isEqualTo("STALE");
        verifyNoInteractions(provider);
    }

    @Test
    void explanationSuppressesOutputWhenConsentIsRevokedAndReenabledDuringGeneration() {
        when(consent.read("owner")).thenReturn(new Consent(true, "v1"), new Consent(true, "v3"));
        when(insights.detail("owner", "B01")).thenReturn(detail("current"));
        when(provider.explain(any())).thenAnswer(invocation -> SleepStoryService.explanationPreview(invocation.getArgument(0)));
        var result = service.explanation("owner", "B01", "current");
        assertThat(result.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(result.observation()).isNull();
    }

    @Test
    void explanationRejectsFabricatedTextWithTheCorrectFingerprint() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.detail("owner", "B01")).thenReturn(detail("current"));
        when(provider.explain(any())).thenReturn(new Explanation("Bạn ngủ tốt hơn 99%", "99 ngày", "Không có", "current"));
        var result = service.explanation("owner", "B01", "current");
        assertThat(result.status()).isEqualTo("FALLBACK");
        assertThat(result.observation()).doesNotContain("99");
    }

    @Test
    void providerRequiresExplicitEnableAndNonProductionProfile() {
        var context = new ApplicationContextRunner().withUserConfiguration(DeterministicSleepStoryProvider.class);
        var enabled = context.withPropertyValues("vitaloom.sleep.story.enabled=true");
        enabled.run(result -> assertThat(result).doesNotHaveBean(Provider.class));
        enabled.withPropertyValues("spring.profiles.active=prod,dev")
                .run(result -> assertThat(result).doesNotHaveBean(Provider.class));
        context.withPropertyValues("spring.profiles.active=dev")
                .run(result -> assertThat(result).doesNotHaveBean(Provider.class));
        enabled.withPropertyValues("spring.profiles.active=dev")
                .run(result -> assertThat(result).hasSingleBean(Provider.class));
    }

    private static Insights snapshot(Item... items) {
        return new Insights(snapshotPeriod(), List.of(items));
    }

    private static Detail detail(String version) { return detail("B01", version); }

    private static Detail detail(String code, String version) {
        var item = item(code, version);
        return new Detail(item.code(), item.category(), item.status(), snapshot(item).period(), item.title(),
                item.summary(), item.sample(), item.evidence(), item.sourceRefs(), item.limitations(),
                item.policyVersion(), version);
    }

    private static Item item(String code, String fingerprint) {
        Period period = snapshotPeriod();
        Map<String, Object> sample;
        Map<String, Object> evidence;
        switch (code) {
            case "A01" -> {
                sample = Map.of("recordedMainDays", 4, "coverageDenominator", 7);
                evidence = Map.of("recordedMainDays", 4, "coverageDenominator", 7);
            }
            case "A02" -> {
                sample = Map.of("eligibleMainDays", 4);
                evidence = Map.of("metGoalDays", 2, "eligibleMainDays", 4);
            }
            case "A03" -> {
                Period baseline = new Period(period.end().minusDays(14), period.end().minusDays(8), period.timeZone());
                Period recent = new Period(period.end().minusDays(7), period.end().minusDays(1), period.timeZone());
                sample = Map.of("baselineMainDays", 3, "recentMainDays", 4);
                evidence = Map.of("baseline", Map.of("period", baseline, "meanMinutes", 400),
                        "recent", Map.of("period", recent, "meanMinutes", 420), "deltaMinutes", 20);
            }
            case "A04" -> {
                sample = Map.of("eligibleRangeDays", 3);
                evidence = Map.of("timeZone", "America/Los_Angeles", "bedtime", Map.of("spanMinutes", 20),
                        "wake", Map.of("spanMinutes", 30));
            }
            case "A05" -> {
                sample = Map.of("weekdayDays", 3, "weekendDays", 2);
                evidence = Map.of("weekday", Map.of("days", 3, "meanMinutes", 420),
                        "weekend", Map.of("days", 2, "meanMinutes", 400), "deltaMinutes", 20);
            }
            case "B01" -> {
                sample = Map.of("ratedMain", 3, "mainWithoutFeeling", 1);
                evidence = Map.of("comfortable", Map.of("count", 1, "denominator", 3),
                        "normal", Map.of("count", 1, "denominator", 3), "tired", Map.of("count", 1, "denominator", 3));
            }
            case "B02" -> {
                sample = Map.of("comfortableDays", 3, "tiredDays", 3);
                evidence = Map.of("comfortable", Map.of("days", 3, "meanMinutes", 420),
                        "tired", Map.of("days", 3, "meanMinutes", 400), "deltaMinutes", 20);
            }
            case "B03" -> {
                Period baseline = new Period(period.end().minusDays(14), period.end().minusDays(8), period.timeZone());
                Period recent = new Period(period.end().minusDays(7), period.end().minusDays(1), period.timeZone());
                sample = Map.of("baselineRatedMain", 3, "recentRatedMain", 4,
                        "baselineMainWithoutFeeling", 1, "recentMainWithoutFeeling", 0);
                evidence = Map.of("baseline", Map.of("period", baseline), "recent", Map.of("period", recent),
                        "deltaPercentagePoints", Map.of("comfortable", 1.0, "normal", 0.0, "tired", -1.0));
            }
            default -> {
                sample = Map.of("ratedMain", 3, "comparableRatedMain", 3);
                evidence = Map.of("exceptionCount", 1, "ratedMain", 3, "exceptions", List.of(
                        Map.of("day", LocalDate.of(2026, 11, 12), "minutes", 480, "goalMinutes", 450)));
            }
        }
        return new Item(code, code.substring(0, 1), "READY", "", true, false, "", sample, evidence,
                List.of("private-source-id"), List.of(), "p2", fingerprint);
    }

    private static Period snapshotPeriod() {
        return new Period(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15), "Asia/Ho_Chi_Minh");
    }
}
