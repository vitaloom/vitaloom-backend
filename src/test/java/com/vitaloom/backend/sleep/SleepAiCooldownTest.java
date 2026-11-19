package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Detail;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Insights;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SleepAiCooldownTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void storyCooldownStartsAtSuccessfulPublicationHasExactBoundaryAndIsOwnerScoped() {
        Harness h = new Harness();
        h.generateDelay = Duration.ofMinutes(2);
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        assertThat(h.clock.instant()).isEqualTo(h.start.plus(Duration.ofMinutes(2)));
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        verify(h.provider, times(1)).generate(any());

        h.clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        h.clock.advance(Duration.ofSeconds(1));
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");

        assertThat(h.service.story("bob").status()).isEqualTo("STORY");
        verify(h.provider, times(3)).generate(any());
    }

    @Test
    void explanationCooldownIsPerOwnerAndInsightAndIndependentFromStory() {
        Harness h = new Harness();
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("EXPLANATION");
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("FALLBACK");
        assertThat(h.service.explanation("alice", "A01", null).status()).isEqualTo("EXPLANATION");
        assertThat(h.service.explanation("bob", "B01", null).status()).isEqualTo("EXPLANATION");
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        h.clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("FALLBACK");
        h.clock.advance(Duration.ofSeconds(1));
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("EXPLANATION");
        verify(h.provider, times(4)).explain(any());
        verify(h.provider, times(1)).generate(any());
    }

    @Test
    void validCurrentCacheHitBypassesCooldownWithoutExtendingIt() {
        SleepAiCache cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        Harness h = new Harness(cache);
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        h.clock.advance(Duration.ofMinutes(14));
        when(cache.readStory(any())).thenAnswer(call -> SleepStoryService.preview(h.evidence("alice")));
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        verify(h.provider, times(1)).generate(any());
        h.clock.advance(Duration.ofMinutes(1));
        when(cache.readStory(any())).thenReturn(null);
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        verify(h.provider, times(2)).generate(any());
    }

    @Test
    void evidenceChangesAndGenerationFailuresDoNotBypassOrConsumeCooldown() {
        Harness h = new Harness();
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        h.version.set("changed");
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        h.clock.advance(Duration.ofMinutes(15));
        doThrow(new IllegalStateException("provider unavailable")).when(h.provider).generate(any());
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        verify(h.cache, times(1)).writeStory(any(), any());
        reset(h.provider);
        when(h.providers.getIfAvailable()).thenReturn(h.provider);
        when(h.provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
    }

    @Test
    void staleProviderOutputAndCacheWriteFailureDoNotStartCooldown() {
        Harness stale = new Harness();
        reset(stale.provider);
        AtomicReference<Boolean> first = new AtomicReference<>(true);
        when(stale.provider.generate(any())).thenAnswer(call -> {
            Evidence original = call.getArgument(0);
            if (first.getAndSet(false)) stale.version.set("after-generation");
            return SleepStoryService.preview(original);
        });
        assertThat(stale.service.story("alice").status()).isEqualTo("FALLBACK");
        assertThat(stale.service.story("alice").status()).isEqualTo("STORY");
        verify(stale.provider, times(2)).generate(any());

        Harness writeFailure = new Harness();
        doThrow(new IllegalStateException("cache unavailable"))
                .when(writeFailure.cache).writeStory(any(), any());
        assertThat(writeFailure.service.story("alice").status()).isEqualTo("FALLBACK");
        assertThat(writeFailure.service.story("alice").status()).isEqualTo("FALLBACK");
        verify(writeFailure.provider, times(2)).generate(any());
    }

    @Test
    void firstSuccessfulGenerationOnNewReportingDayBypassesPriorCooldown() {
        Harness h = new Harness();
        h.clock.set(DAY.atTime(23, 59).atZone(REPORT_ZONE).toInstant());
        h.periodEnd.set(DAY);
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        h.periodEnd.set(DAY.plusDays(1));
        h.clock.advance(Duration.ofMinutes(2));
        doReturn(null).when(h.provider).generate(any());
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        doAnswer(call -> SleepStoryService.preview(call.getArgument(0))).when(h.provider).generate(any());
        assertThat(h.service.story("alice").status()).isEqualTo("STORY");
        assertThat(h.service.story("alice").status()).isEqualTo("FALLBACK");
        verify(h.provider, times(3)).generate(any());
    }

    @Test
    void explanationCacheHitDoesNotExtendCooldownMeasuredFromCompletion() {
        Harness h = new Harness();
        doAnswer(call -> {
            h.clock.advance(Duration.ofMinutes(2));
            return SleepStoryService.explanationPreview(call.getArgument(0));
        }).when(h.provider).explain(any());
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("EXPLANATION");
        h.clock.advance(Duration.ofMinutes(4));
        var evidence = new Evidence(snapshotPeriod(DAY), null, List.of(candidate("B01", DAY, "same")));
        when(h.cache.readExplanation(any())).thenReturn(SleepStoryService.explanationPreview(evidence));
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("EXPLANATION");
        verify(h.provider, times(1)).explain(any());
        when(h.cache.readExplanation(any())).thenReturn(null);
        h.clock.advance(Duration.ofSeconds(59));
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("FALLBACK");
        h.clock.advance(Duration.ofSeconds(1));
        verify(h.provider, times(1)).explain(any());
        assertThat(h.service.explanation("alice", "B01", null).status()).isEqualTo("EXPLANATION");
        verify(h.provider, times(2)).explain(any());
    }

    @Test
    void unsuccessfulExplanationAttemptsAllowImmediateGenerationWithCurrentEvidence() {
        for (String failure : List.of("provider", "invalid", "stale", "consent", "cache-read", "cache-write")) {
            Harness h = new Harness();
            doAnswer(call -> {
                Evidence original = call.getArgument(0);
                switch (failure) {
                    case "provider" -> throw new IllegalStateException("fake unavailable");
                    case "invalid" -> { return null; }
                    case "stale" -> h.version.set("updated");
                    case "consent" -> when(h.consent.read(any())).thenReturn(new Consent(false, "off"));
                    default -> { }
                }
                return SleepStoryService.explanationPreview(original);
            }).when(h.provider).explain(any());
            if (failure.equals("cache-read")) {
                when(h.cache.readExplanation(any())).thenThrow(new IllegalStateException("fake cache unavailable"));
            }
            if (failure.equals("cache-write")) {
                doThrow(new IllegalStateException("fake cache unavailable")).when(h.cache).writeExplanation(any(), any());
            }
            String expected = failure.equals("stale") ? "STALE" : failure.equals("consent") ? "CONSENT_REQUIRED" : "FALLBACK";
            assertThat(h.service.explanation("alice", "B01", null).status()).as(failure).isEqualTo(expected);
            when(h.consent.read(any())).thenReturn(new Consent(true, "re-enabled"));
            doReturn(null).when(h.cache).readExplanation(any());
            doNothing().when(h.cache).writeExplanation(any(), any());
            doAnswer(call -> SleepStoryService.explanationPreview(call.getArgument(0))).when(h.provider).explain(any());
            var result = h.service.explanation("alice", "B01", null);
            assertThat(result.status()).as(failure).isEqualTo("EXPLANATION");
            assertThat(result.evidenceFingerprint()).isEqualTo(h.version.get());
            verify(h.provider, times(failure.equals("cache-read") ? 1 : 2)).explain(any());
        }
    }

    @Test
    void invalidOrConsentSuppressedStoryDoesNotStartCooldown() {
        for (boolean revoke : List.of(false, true)) {
            Harness h = new Harness();
            doAnswer(call -> {
                if (revoke) when(h.consent.read(any())).thenReturn(new Consent(false, "off"));
                return revoke ? SleepStoryService.preview(call.getArgument(0)) : null;
            }).when(h.provider).generate(any());
            assertThat(h.service.story("alice").status()).isEqualTo(revoke ? "CONSENT_REQUIRED" : "FALLBACK");
            when(h.consent.read(any())).thenReturn(new Consent(true, "re-enabled"));
            doAnswer(call -> SleepStoryService.preview(call.getArgument(0))).when(h.provider).generate(any());
            assertThat(h.service.story("alice").status()).isEqualTo("STORY");
            verify(h.provider, times(2)).generate(any());
        }
    }

    private static final class Harness {
        final MutableClock clock = new MutableClock(DAY.atTime(12, 0).atZone(REPORT_ZONE).toInstant());
        final Instant start = clock.instant();
        final AtomicReference<String> version = new AtomicReference<>("same");
        final AtomicReference<LocalDate> periodEnd = new AtomicReference<>(DAY);
        final ConsentStore consent = mock(ConsentStore.class);
        final SleepInsightsStatisticsService insights = mock(SleepInsightsStatisticsService.class);
        final Provider provider = mock(Provider.class);
        final ObjectProvider<Provider> providers = mock(ObjectProvider.class);
        final SleepAiCache cache;
        final SleepStoryService service;
        Duration generateDelay = Duration.ZERO;

        Harness() { this(mock(SleepAiCache.class, CALLS_REAL_METHODS)); }
        Harness(SleepAiCache cache) {
            this.cache = cache;
            when(consent.read(any())).thenReturn(new Consent(true, "on"));
            when(insights.fullInsights(any(), any())).thenAnswer(call -> snapshot(periodEnd.get(), version.get()));
            when(insights.detail(any(), any())).thenAnswer(call -> detail(call.getArgument(1), periodEnd.get(), version.get()));
            when(providers.getIfAvailable()).thenReturn(provider);
            when(provider.generate(any())).thenAnswer(call -> {
                if (!generateDelay.isZero()) clock.advance(generateDelay);
                return SleepStoryService.preview(call.getArgument(0));
            });
            when(provider.explain(any())).thenAnswer(call -> SleepStoryService.explanationPreview(call.getArgument(0)));
            service = new SleepStoryService(consent, insights, providers, cache, clock);
        }

        Evidence evidence(String owner) { return new Evidence(snapshotPeriod(periodEnd.get()), null, List.of(candidate("B01", periodEnd.get(), version.get()))); }
    }

    private static Insights snapshot(LocalDate end, String version) { return new Insights(snapshotPeriod(end), List.of(item("A01", "a"), item("B01", version))); }
    private static Detail detail(String code, LocalDate end, String version) {
        Item item = item(code, version);
        return new Detail(item.code(), item.category(), item.status(), snapshotPeriod(end), item.title(), item.summary(),
                item.sample(), item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
    }
    private static Item item(String code, String version) {
        Map<String, Object> sample = "A01".equals(code) ? Map.of("recordedMainDays", 3, "coverageDenominator", 7)
                : Map.of("ratedMain", 3, "mainWithoutFeeling", 0);
        Map<String, Object> evidence = "A01".equals(code) ? Map.of("recordedMainDays", 3, "coverageDenominator", 7)
                : Map.of("comfortable", Map.of("count", 1, "denominator", 3), "normal", Map.of("count", 1, "denominator", 3),
                        "tired", Map.of("count", 1, "denominator", 3));
        return new Item(code, code.substring(0, 1), "READY", "", true, false, "", sample, evidence,
                List.of(), List.of(), "policy", version);
    }
    private static SleepStoryService.Candidate candidate(String code, LocalDate end, String version) {
        Item item = item(code, version);
        return new SleepStoryService.Candidate(code, item.policyVersion(), version, snapshotPeriod(end), item.sample(), item.evidence());
    }
    private static Period snapshotPeriod(LocalDate end) { return new Period(end.minusDays(6), end, REPORT_ZONE.getId()); }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        void set(Instant value) { now = value; }
        @Override public ZoneId getZone() { return REPORT_ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
