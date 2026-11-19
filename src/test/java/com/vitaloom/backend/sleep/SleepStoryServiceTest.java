package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.service.DeterministicSleepStoryProvider;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepAiCache;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test
    void redisReadFailureUsesCurrentFallbackWithoutCallingProviderOrWriting() {
        SleepAiCache cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var cachedService = new SleepStoryService(consent, insights, providerLookup(), cache);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(insights.detail("owner", "B01")).thenReturn(detail("b"));
        when(cache.readStory(any())).thenThrow(new IllegalStateException("offline"));
        when(cache.readExplanation(any())).thenThrow(new IllegalStateException("offline"));
        assertThat(cachedService.story("owner").status()).isEqualTo("FALLBACK");
        assertThat(cachedService.explanation("owner", "B01", null).status()).isEqualTo("FALLBACK");
        verifyNoInteractions(provider);
        verify(cache, never()).writeStory(any(), any());
        verify(cache, never()).writeExplanation(any(), any());
    }

    @Test
    void invalidCachedOutputsAreNotPublishedOverwrittenOrRegenerated() {
        SleepAiCache cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var cachedService = new SleepStoryService(consent, insights, providerLookup(), cache);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(insights.detail("owner", "B01")).thenReturn(detail("b"));
        when(cache.readStory(any())).thenReturn(new Story("invalid", List.of()));
        when(cache.readExplanation(any())).thenReturn(new Explanation("invalid", "invalid", "invalid", "b"));
        assertThat(cachedService.story("owner").status()).isEqualTo("FALLBACK");
        assertThat(cachedService.explanation("owner", "B01", null).status()).isEqualTo("FALLBACK");
        verifyNoInteractions(provider);
        verify(cache, never()).writeStory(any(), any());
        verify(cache, never()).writeExplanation(any(), any());
    }

    @Test
    void cacheWriteFailureUsesNonAiFallbackForBothPaths() {
        SleepAiCache cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var cachedService = new SleepStoryService(consent, insights, providerLookup(), cache);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(insights.detail("owner", "B01")).thenReturn(detail("b"));
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        when(provider.explain(any())).thenAnswer(call -> SleepStoryService.explanationPreview(call.getArgument(0)));
        doThrow(new IllegalStateException("offline")).when(cache).writeStory(any(), any());
        doThrow(new IllegalStateException("offline")).when(cache).writeExplanation(any(), any());
        assertThat(cachedService.story("owner").status()).isEqualTo("FALLBACK");
        assertThat(cachedService.explanation("owner", "B01", null).status()).isEqualTo("FALLBACK");
    }

    @Test
    void staleOrWithdrawnGeneratedOutputIsNeverStored() {
        SleepAiCache cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var cachedService = new SleepStoryService(consent, insights, providerLookup(), cache);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "old")), snapshot(item("B01", "new")));
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        assertThat(cachedService.story("owner").status()).isEqualTo("FALLBACK");
        when(insights.detail("owner", "B01")).thenReturn(detail("old"), detail("new"));
        when(provider.explain(any())).thenAnswer(call -> SleepStoryService.explanationPreview(call.getArgument(0)));
        assertThat(cachedService.explanation("owner", "B01", null).status()).isEqualTo("STALE");
        when(consent.read("owner")).thenReturn(new Consent(true, "on"), new Consent(true, "re-enabled"));
        assertThat(cachedService.story("owner").status()).isEqualTo("CONSENT_REQUIRED");
        when(consent.read("owner")).thenReturn(new Consent(true, "on"), new Consent(false, "off"));
        assertThat(cachedService.explanation("owner", "B01", null).status()).isEqualTo("CONSENT_REQUIRED");
        verify(cache, never()).writeStory(any(), any());
        verify(cache, never()).writeExplanation(any(), any());
    }

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
    void retriesOnlyOneTypedTransientProviderFailure() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenThrow(new SleepStoryService.TransientProviderException("temporary"))
                .thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        assertThat(service.story("owner").status()).isEqualTo("STORY");
        verify(provider, times(2)).generate(any());
    }

    @Test
    void neverMakesThirdAttemptAfterTwoTransientFailures() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenThrow(new SleepStoryService.TransientProviderException("temporary"));
        assertThat(service.story("owner").status()).isEqualTo("FALLBACK");
        verify(provider, times(2)).generate(any());
    }

    @Test
    void transientFailureDoesNotRetryWhenConsentWasRevoked() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"), new Consent(false, "off"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenThrow(new SleepStoryService.TransientProviderException("temporary"));
        assertThat(service.story("owner").status()).isEqualTo("CONSENT_REQUIRED");
        verify(provider).generate(any());
    }

    @Test
    void revocationDuringRetryEvidenceRefreshPreventsSecondProviderCall() {
        var currentConsent = new java.util.concurrent.atomic.AtomicReference<>(new Consent(true, "on"));
        when(consent.read("owner")).thenAnswer(call -> currentConsent.get());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(insights.fullInsights("owner", null)).thenAnswer(call -> {
            if (reads.incrementAndGet() == 2) currentConsent.set(new Consent(false, "revoked"));
            return snapshot(item("B01", "b"));
        });
        when(provider.generate(any())).thenThrow(new SleepStoryService.TransientProviderException("temporary"))
                .thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));

        var response = service.story("owner");

        assertThat(response.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(response.observations()).isNull();
        verify(provider).generate(any());
    }

    @Test
    void consentReadReturningAtDeadlineCannotPublishOrWriteCache() {
        var ticker = new java.util.concurrent.atomic.AtomicLong(50);
        var deadlineCache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        when(consent.read("owner")).thenAnswer(call -> {
            if (org.mockito.Mockito.mockingDetails(consent).getInvocations().size() >= 2)
                ticker.set(50 + TimeUnit.SECONDS.toNanos(20));
            return new Consent(true, "on");
        });
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        var bounded = new SleepStoryService(consent, insights, providerLookup(), deadlineCache, java.time.Clock.systemUTC(),
                TimeUnit.SECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(100), ticker::get);

        var response = bounded.story("owner");

        assertThat(response.status()).isNotEqualTo("STORY");
        assertThat(response.observations()).isNull();
        verify(deadlineCache, never()).writeStory(any(), any(), any());
    }

    @Test
    void storyAndExplanationSuppressResultsAtTheirExactBudgets() {
        var cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var storyTicker = new java.util.concurrent.atomic.AtomicLong(100);
        var bounded = new SleepStoryService(consent, insights, providerLookup(), cache, java.time.Clock.systemUTC(),
                TimeUnit.SECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(100),
                () -> storyTicker.get());
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenAnswer(call -> {
            storyTicker.addAndGet(TimeUnit.SECONDS.toNanos(20));
            return SleepStoryService.preview(call.getArgument(0));
        });
        var storyResult = bounded.story("owner");
        assertThat(storyResult.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(storyResult.observations()).isNull();
        verify(cache, never()).writeStory(any(), any());

        var explanationTicker = new java.util.concurrent.atomic.AtomicLong(200);
        var explanationBounded = new SleepStoryService(consent, insights, providerLookup(), cache,
                java.time.Clock.systemUTC(), TimeUnit.SECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(10),
                TimeUnit.MILLISECONDS.toNanos(100), explanationTicker::get);
        when(insights.detail("owner", "B01")).thenReturn(detail("b"));
        when(provider.explain(any())).thenAnswer(call -> {
            explanationTicker.addAndGet(TimeUnit.SECONDS.toNanos(10));
            return SleepStoryService.explanationPreview(call.getArgument(0));
        });
        var explanationResult = explanationBounded.explanation("owner", "B01", null);
        assertThat(explanationResult.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(explanationResult.observation()).isNull();
        verify(cache, never()).writeExplanation(any(), any());
    }

    @Test
    void suppressesGeneratedStoryWhenFinalEvidenceReadConsumesRemainingBudget() {
        var cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        var ticker = new java.util.concurrent.atomic.AtomicLong(100);
        var bounded = new SleepStoryService(consent, insights, providerLookup(), cache, java.time.Clock.systemUTC(),
                TimeUnit.SECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(100), ticker::get);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenAnswer(call -> {
            if (org.mockito.Mockito.mockingDetails(insights).getInvocations().size() >= 2) {
                ticker.set(100 + TimeUnit.SECONDS.toNanos(20));
            }
            return snapshot(item("B01", "b"));
        });
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        var response = bounded.story("owner");
        assertThat(response.status()).isIn("CONSENT_REQUIRED", "INELIGIBLE");
        assertThat(response.observations()).isNull();
        verify(cache, never()).writeStory(any(), any());
    }

    @Test
    void blockedProviderIsCancelledAtBudgetAndReturnsCurrentFallback() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(invocation -> {
            try { releaseProvider.await(); } catch (InterruptedException ignored) { releaseProvider.await(); }
            return SleepStoryService.preview(invocation.getArgument(0));
        });
        var bounded = new SleepStoryService(consent, insights, providerLookup(), SleepAiCache.bypass(),
                java.time.Clock.systemUTC(), TimeUnit.MILLISECONDS.toNanos(120), TimeUnit.MILLISECONDS.toNanos(80),
                TimeUnit.MILLISECONDS.toNanos(70), System::nanoTime);
        long started = System.nanoTime();
        SleepStoryService.Response response;
        try {
            response = bounded.story("owner");
        } finally {
            releaseProvider.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).isLessThan(300);
        assertThat(response.status()).isEqualTo("FALLBACK");
        verify(provider).generate(any());
    }

    @Test
    void expiredPublicationPreservesAnotherRequestsSuccessfulCooldown() throws Exception {
        var ticker = new AtomicLong();
        var clock = mock(Clock.class);
        var firstReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        when(clock.instant()).thenAnswer(call -> {
            firstReady.countDown();
            assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            return Instant.EPOCH;
        }).thenReturn(Instant.EPOCH);
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "b")));
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        var bounded = new SleepStoryService(consent, insights, providerLookup(), SleepAiCache.bypass(), clock,
                TimeUnit.SECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(10), TimeUnit.SECONDS.toNanos(1), ticker::get);

        var expired = CompletableFuture.supplyAsync(() -> bounded.story("owner"));
        try {
            assertThat(firstReady.await(5, TimeUnit.SECONDS)).isTrue();
            ticker.set(TimeUnit.SECONDS.toNanos(5));
            // A different evidence identity can publish while the earlier flight is still in progress.
            when(insights.fullInsights("owner", null)).thenReturn(snapshot(item("B01", "new-evidence")));
            assertThat(bounded.story("owner").status()).isEqualTo("STORY");
            ticker.set(TimeUnit.SECONDS.toNanos(20));
        } finally {
            releaseFirst.countDown();
        }
        var expiredResult = expired.get(5, TimeUnit.SECONDS);
        assertThat(expiredResult.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(expiredResult.observations()).isNull();
        assertThat(bounded.story("owner").notice()).isNotBlank();
        verify(provider, times(2)).generate(any());
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
        context.withPropertyValues("spring.profiles.active=test")
                .run(result -> assertThat(result).doesNotHaveBean(Provider.class));
        context.withPropertyValues("spring.profiles.active=test", "vitaloom.sleep.story.enabled=true")
                .run(result -> assertThat(result).hasSingleBean(Provider.class));
        context.withPropertyValues("spring.profiles.active=prod,test", "vitaloom.sleep.story.enabled=true")
                .run(result -> assertThat(result).doesNotHaveBean(Provider.class));
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
