package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.ExplanationResponse;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.sleep.service.SleepStoryService.Response;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Detail;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Insights;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Item;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SleepAiSingleFlightTest {
    private static final long WAIT_SECONDS = 5;

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void consentWithdrawalAtCachePublicationSuppressesLeaderAndWaiter(boolean explanation,
            boolean beforeDispatch) throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var consentState = new AtomicReference<>(new Consent(true, "enabled-1"));
        when(fixture.consent.read("owner")).thenAnswer(call -> consentState.get());
        when(provider.generate(any())).thenAnswer(call -> SleepStoryService.preview(call.getArgument(0)));
        when(provider.explain(any())).thenAnswer(call -> SleepStoryService.explanationPreview(call.getArgument(0)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> withdrawAtPublication = call -> {
            entered.countDown();
            await(release);
            if (beforeDispatch) consentState.set(new Consent(false, "withdrawn-2"));
            call.callRealMethod();
            if (!beforeDispatch) consentState.set(new Consent(false, "withdrawn-2"));
            return null;
        };
        if (explanation) doAnswer(withdrawAtPublication).when(fixture.cache).writeExplanation(any(), any(), any());
        else doAnswer(withdrawAtPublication).when(fixture.cache).writeStory(any(), any(), any());

        java.util.function.Supplier<?> request = explanation
                ? () -> fixture.service.explanation("owner", "B01", "version")
                : () -> fixture.service.story("owner");
        var leader = caller(request);
        await(entered); // Provider output and the existing post-provider consent check have completed.
        var waiter = caller(request);
        try {
            awaitJoined(waiter, "singleFlight");
        } finally {
            release.countDown();
        }
        if (explanation) {
            assertThat(leader.explanationResult().status()).isEqualTo("CONSENT_REQUIRED");
            assertThat(waiter.explanationResult().status()).isEqualTo("CONSENT_REQUIRED");
            if (beforeDispatch) verify(fixture.cache, never()).writeExplanation(any(), any());
            doCallRealMethod().when(fixture.cache).writeExplanation(any(), any(), any());
        } else {
            assertThat(leader.response().status()).isEqualTo("CONSENT_REQUIRED");
            assertThat(waiter.response().status()).isEqualTo("CONSENT_REQUIRED");
            if (beforeDispatch) verify(fixture.cache, never()).writeStory(any(), any());
            doCallRealMethod().when(fixture.cache).writeStory(any(), any(), any());
        }
        consentState.set(new Consent(true, "enabled-3"));
        // A withdrawn publication must neither retain its flight nor start a successful-generation cooldown.
        if (explanation) {
            assertThat(fixture.service.explanation("owner", "B01", "version").status()).isEqualTo("EXPLANATION");
            verify(provider, times(2)).explain(any());
        } else {
            assertThat(fixture.service.story("owner").status()).isEqualTo("STORY");
            verify(provider, times(2)).generate(any());
        }
    }

    @Test
    void equalStoryRequestsShareOneProviderCall() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return SleepStoryService.preview(call.getArgument(0));
        });

        var first = caller(() -> fixture.service.story("owner"));
        await(entered);
        var second = caller(() -> fixture.service.story("owner"));
        try {
            awaitJoined(second, "singleFlight");
        } finally {
            release.countDown();
        }

        assertThat(first.response().status()).isEqualTo("STORY");
        assertThat(second.response().status()).isEqualTo("STORY");
        verify(provider, times(1)).generate(any());
    }

    @Test
    void equalExplanationRequestsShareOneProviderCall() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.explain(any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return SleepStoryService.explanationPreview(call.getArgument(0));
        });

        var first = caller(() -> fixture.service.explanation("owner", "B01", "version"));
        await(entered);
        var second = caller(() -> fixture.service.explanation("owner", "B01", "version"));
        try {
            awaitJoined(second, "singleFlight");
        } finally {
            release.countDown();
        }

        assertThat(first.explanationResult().status()).isEqualTo("EXPLANATION");
        assertThat(second.explanationResult().status()).isEqualTo("EXPLANATION");
        verify(provider, times(1)).explain(any());
    }

    @Test
    void storyFlightsDoNotCombineDifferentOwnersOrEvidence() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var firstEntered = new CountDownLatch(1);
        var bothEvidenceProvidersEntered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(call -> {
            firstEntered.countDown();
            bothEvidenceProvidersEntered.countDown();
            await(release);
            return SleepStoryService.preview(call.getArgument(0));
        });

        var owner = caller(() -> fixture.service.story("owner"));
        await(firstEntered);
        var otherOwner = caller(() -> fixture.service.story("other-owner"));
        try {
            await(bothEvidenceProvidersEntered);
        } finally {
            release.countDown();
        }
        assertThat(owner.response().status()).isEqualTo("STORY");
        assertThat(otherOwner.response().status()).isEqualTo("STORY");

        var differentEvidenceProvider = mock(Provider.class);
        var evidenceFixture = fixture(differentEvidenceProvider);
        var firstEvidenceEntered = new CountDownLatch(1);
        var bothEvidenceRequestsEntered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(differentEvidenceProvider.generate(any())).thenAnswer(call -> {
            firstEvidenceEntered.countDown();
            bothEvidenceRequestsEntered.countDown();
            await(finish);
            return SleepStoryService.preview(call.getArgument(0));
        });
        when(evidenceFixture.insights.fullInsights("owner", null)).thenReturn(snapshot("first"), snapshot("second"));
        var firstEvidence = caller(() -> evidenceFixture.service.story("owner"));
        await(firstEvidenceEntered);
        var secondEvidence = caller(() -> evidenceFixture.service.story("owner"));
        try {
            await(bothEvidenceRequestsEntered);
        } finally {
            finish.countDown();
        }
        assertThat(firstEvidence.response().status()).isIn("STORY", "FALLBACK");
        assertThat(secondEvidence.response().status()).isIn("STORY", "FALLBACK");
        verify(differentEvidenceProvider, times(2)).generate(any());
    }

    @Test
    void failedStoryFlightIsRemovedSoLaterRequestCanGenerate() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(call -> {
            if (entered.getCount() > 0) {
                entered.countDown();
                await(release);
                throw new IllegalStateException("synthetic provider failure");
            }
            return SleepStoryService.preview(call.getArgument(0));
        });
        var first = caller(() -> fixture.service.story("owner"));
        await(entered);
        var second = caller(() -> fixture.service.story("owner"));
        try {
            awaitJoined(second, "singleFlight");
        } finally {
            release.countDown();
        }
        assertThat(first.response().status()).isEqualTo("FALLBACK");
        assertThat(second.response().status()).isEqualTo("FALLBACK");
        assertThat(fixture.service.story("owner").status()).isEqualTo("STORY");
        verify(provider, times(2)).generate(any());
    }

    @Test
    void consentWithdrawalDuringSharedStoryGenerationSuppressesPublication() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var consentState = new AtomicReference<>(new Consent(true, "enabled-1"));
        when(fixture.consent.read("owner")).thenAnswer(call -> consentState.get());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return SleepStoryService.preview(call.getArgument(0));
        });
        var first = caller(() -> fixture.service.story("owner"));
        await(entered);
        var second = caller(() -> fixture.service.story("owner"));
        try {
            awaitJoined(second, "singleFlight");
            consentState.set(new Consent(false, "withdrawn-2"));
        } finally {
            release.countDown();
        }
        assertThat(first.response().status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(second.response().status()).isEqualTo("CONSENT_REQUIRED");
        verify(provider, times(1)).generate(any());
        verify(fixture.cache, never()).writeStory(any(), any());
    }

    @Test
    void consentWithdrawalDuringSharedExplanationSuppressesPublication() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var consentState = new AtomicReference<>(new Consent(true, "enabled-1"));
        when(fixture.consent.read("owner")).thenAnswer(call -> consentState.get());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.explain(any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return SleepStoryService.explanationPreview(call.getArgument(0));
        });
        var first = caller(() -> fixture.service.explanation("owner", "B01", "version"));
        await(entered);
        var second = caller(() -> fixture.service.explanation("owner", "B01", "version"));
        try {
            awaitJoined(second, "singleFlight");
            consentState.set(new Consent(false, "withdrawn-2"));
        } finally {
            release.countDown();
        }
        assertThat(first.explanationResult().status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(second.explanationResult().status()).isEqualTo("CONSENT_REQUIRED");
        verify(provider, times(1)).explain(any());
        verify(fixture.cache, never()).writeExplanation(any(), any());
    }

    @Test
    void consentRevisionChangeDoesNotJoinPreviousStoryFlight() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var consentState = new AtomicReference<>(new Consent(true, "enabled-1"));
        when(fixture.consent.read("owner")).thenAnswer(call -> consentState.get());
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.generate(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                await(releaseFirst);
            } else {
                secondEntered.countDown();
                await(releaseSecond);
            }
            return SleepStoryService.preview(call.getArgument(0));
        });

        var oldConsent = caller(() -> fixture.service.story("owner"));
        await(firstEntered);
        consentState.set(new Consent(false, "withdrawn-2"));
        consentState.set(new Consent(true, "enabled-3"));
        var newConsent = caller(() -> fixture.service.story("owner"));
        try {
            await(secondEntered);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
        assertThat(oldConsent.response().status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(newConsent.response().status()).isEqualTo("STORY");
        verify(provider, times(2)).generate(any());
    }

    @Test
    void interruptingWaiterDoesNotCancelSharedStoryGeneration() throws Exception {
        var provider = mock(Provider.class);
        var fixture = fixture(provider);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.generate(any())).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return SleepStoryService.preview(call.getArgument(0));
        });
        var leader = caller(() -> fixture.service.story("owner"));
        await(entered);
        var waiter = caller(() -> fixture.service.story("owner"));
        awaitJoined(waiter, "singleFlight");

        waiter.thread().interrupt();
        assertThat(waiter.response().status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(release.getCount()).isEqualTo(1);
        verify(provider, times(1)).generate(any());

        release.countDown();
        assertThat(leader.response().status()).isEqualTo("STORY");
        verify(provider, times(1)).generate(any());
    }

    private static Fixture fixture(Provider provider) {
        var consent = mock(ConsentStore.class);
        when(consent.read(any())).thenReturn(new Consent(true, "enabled-1"));
        var insights = mock(SleepInsightsStatisticsService.class);
        when(insights.fullInsights(any(), any())).thenReturn(snapshot("version"));
        when(insights.detail(any(), eq("B01"))).thenReturn(detail("version"));
        @SuppressWarnings("unchecked") ObjectProvider<Provider> providerLookup = mock(ObjectProvider.class);
        when(providerLookup.getIfAvailable()).thenReturn(provider);
        var cache = mock(SleepAiCache.class, CALLS_REAL_METHODS);
        return new Fixture(new SleepStoryService(consent, insights, providerLookup, cache), consent, insights, cache);
    }

    private static Insights snapshot(String version) {
        var period = period();
        var item = new Item("B01", "B", "READY", "", true, false, "", Map.of("ratedMain", 3, "mainWithoutFeeling", 0),
                Map.of("comfortable", Map.of("count", 2, "denominator", 3),
                        "normal", Map.of("count", 1, "denominator", 3), "tired", Map.of("count", 0, "denominator", 3)),
                List.of(), List.of(), "policy", version);
        return new Insights(period, List.of(item));
    }

    private static Detail detail(String version) {
        var item = snapshot(version).items().getFirst();
        return new Detail(item.code(), item.category(), item.status(), period(), item.title(), item.summary(), item.sample(),
                item.evidence(), item.sourceRefs(), item.limitations(), item.policyVersion(), item.evidenceVersion());
    }

    private static Period period() {
        return new Period(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15), "Asia/Ho_Chi_Minh");
    }

    private static Caller caller(java.util.function.Supplier<?> action) {
        var result = new AtomicReference<Object>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { result.set(action.get()); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "sleep-singleflight-test-caller");
        thread.start();
        return new Caller(thread, result, failure);
    }

    private static void awaitJoined(Caller caller, String serviceFrame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!caller.thread().isAlive()) throw new AssertionError("request completed before joining the active flight");
            var state = caller.thread().getState();
            boolean inFlightWait = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            boolean serviceFramePresent = List.of(caller.thread().getStackTrace()).stream()
                    .anyMatch(frame -> frame.getClassName().equals(SleepStoryService.class.getName())
                            && frame.getMethodName().equals(serviceFrame));
            boolean futureWait = List.of(caller.thread().getStackTrace()).stream()
                    .anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                            && (frame.getMethodName().equals("get") || frame.getMethodName().equals("timedGet")));
            if (inFlightWait && serviceFramePresent && futureWait) return;
            Thread.yield();
        }
        throw new AssertionError("request did not join the active single-flight within " + WAIT_SECONDS + " seconds");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test latch");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test latch", interrupted);
        }
    }

    private record Caller(Thread thread, AtomicReference<Object> result, AtomicReference<Throwable> failure) {
        Response response() throws Exception { join(); return (Response) result.get(); }
        ExplanationResponse explanationResult() throws Exception { join(); return (ExplanationResponse) result.get(); }
        private void join() throws Exception {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(thread.isAlive()).as("request thread completed").isFalse();
            if (failure.get() != null) throw new AssertionError("request failed", failure.get());
        }
    }

    private record Fixture(SleepStoryService service, ConsentStore consent, SleepInsightsStatisticsService insights,
            SleepAiCache cache) {}
}
