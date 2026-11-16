package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.contract.SleepGoalReadQuery;
import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsight;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.sleep.contract.dto.SleepGoalWindow;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.controller.SleepStoryController;
import com.vitaloom.backend.sleep.controller.exceptionhandler.SleepExceptionHandler;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import com.vitaloom.backend.statistics.service.impl.SleepInsightsStatisticsServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SleepAiEvidenceIntegrationTest {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId SELECTED_ZONE = ZoneId.of("America/Los_Angeles");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void realStatisticsSendsFullEvidenceAndSupportsEveryPublishedExplanation() {
        Harness harness = new Harness();
        Evidence first = harness.capture();
        assertThat(first.candidates()).extracting(SleepStoryService.Candidate::code)
                .containsExactly("A01", "A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04");
        var a03 = candidate(first, "A03");
        assertThat(a03.evidence()).containsKeys("baseline", "recent", "deltaMinutes");
        assertThat(((Map<?, ?>) a03.evidence().get("baseline")).get("period")).isInstanceOf(Period.class);
        assertThat(((Map<?, ?>) a03.evidence().get("recent")).get("period")).isInstanceOf(Period.class);
        assertThat(candidate(first, "A04").evidence()).containsEntry("timeZone", SELECTED_ZONE.getId());
        assertThat(candidate(first, "A02").evidence()).containsKey("metGoalDays");
        assertThat(candidate(first, "B04").evidence()).containsKey("exceptions");
        assertThat(first.fingerprint()).isEqualTo(harness.capture().fingerprint());

        for (String code : List.of("A01", "A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04")) {
            harness.clock.advance(Duration.ofMinutes(5));
            var response = harness.service.explanation("owner", code, null, SELECTED_ZONE);
            assertThat(response.status()).as(code).isEqualTo("EXPLANATION");
            assertThat(response.observation()).as(code).isNotBlank();
            assertThat(response.evidenceBasis()).as(code).isNotBlank();
            assertThat(response.limitations()).as(code).isNotBlank();
            assertThat(words(response.observation()) + words(response.evidenceBasis()) + words(response.limitations()))
                    .as(code + " explanation word budget").isLessThanOrEqualTo(150);
        }
        for (String code : List.of("A02", "A03", "A04", "A05", "B01", "B02", "B03", "B04")) {
            harness.clock.advance(Duration.ofMinutes(15));
            harness.storyCode.set(code);
            var story = harness.service.story("owner", SELECTED_ZONE);
            assertThat(story.status()).as(code).isEqualTo("STORY");
            assertThat(story.observations()).hasSize(1);
            assertThat(words(story.headline()) + words(story.observations().getFirst().text())
                    + words(story.observations().getFirst().dataContext())).as(code + " story word budget")
                    .isLessThanOrEqualTo(120);
        }
    }

    @Test
    void storyRejectsProviderResultAfterDateFeelingMissingFeelingOrGoalChanges() {
        assertStoryStale(harness -> harness.clock.set(TODAY.plusDays(1).atTime(12, 0).atZone(REPORT_ZONE).toInstant()));
        assertStoryStale(harness -> harness.changeFeeling("session-0", "TIRED"));
        assertStoryStale(harness -> harness.changeFeeling("session-2", null));
        assertStoryStale(harness -> harness.goals.set(Map.of()));
    }

    @Test
    void httpCooldownReturnsCurrentFallbackWithoutAnAutomaticSecondProviderCall() throws Exception {
        Harness harness = new Harness();
        var mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var owner = new TestingAuthenticationToken("owner", "unused");

        mvc.perform(get("/api/sleep/recent/story").principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        String fingerprint = SleepStoryService.preview(harness.sent.get())
                .observations().getFirst().evidenceFingerprint();
        mvc.perform(get("/api/sleep/recent/story").principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.observations[0].evidenceFingerprint").value(fingerprint))
                .andExpect(jsonPath("$.notice").exists());
        verify(harness.provider, times(1)).generate(any());

        String explanationFingerprint = candidate(harness.sent.get(), "B01").fingerprint();
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.evidenceFingerprint").value(explanationFingerprint));
        verify(harness.provider, times(1)).explain(any());

        harness.changeFeeling("session-2", null);
        var current = harness.statistics.detail("owner", "B01");
        assertThat(current.evidenceVersion()).isNotEqualTo(explanationFingerprint);
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.evidenceFingerprint").value(current.evidenceVersion()));
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"A01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        verify(harness.provider, times(2)).explain(any());
        harness.clock.advance(Duration.ofMinutes(15));
        verify(harness.provider, times(1)).generate(any());
        verify(harness.provider, times(2)).explain(any());
        mvc.perform(get("/api/sleep/recent/story").principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"))
                .andExpect(jsonPath("$.notice").doesNotExist());
        verify(harness.provider, times(2)).generate(any());
    }

    @Test
    void selectedA04ZoneControlsCandidateAndIsPartOfStableIdentity() {
        Harness harness = new Harness();
        Evidence losAngeles = harness.capture(SELECTED_ZONE);
        Evidence newYork = harness.capture(ZoneId.of("America/New_York"));
        Evidence repeated = harness.capture(SELECTED_ZONE);
        assertThat(candidate(losAngeles, "A04").evidence()).containsEntry("timeZone", SELECTED_ZONE.getId());
        assertThat(newYork.candidates()).noneMatch(item -> "A04".equals(item.code()));
        assertThat(losAngeles.selectedZone()).isEqualTo(SELECTED_ZONE.getId());
        assertThat(losAngeles.fingerprint()).isNotEqualTo(newYork.fingerprint());
        assertThat(losAngeles.fingerprint()).isEqualTo(repeated.fingerprint());
    }

    @Test
    void explanationRechecksDateFeelingsAndGoalFromRealStatistics() {
        Harness date = new Harness();
        date.beforePublish.set(h -> h.clock.set(TODAY.plusDays(1).atTime(12, 0).atZone(REPORT_ZONE).toInstant()));
        assertThat(date.service.explanation("owner", "B01", null).status()).isEqualTo("STALE");
        Harness feeling = new Harness();
        feeling.beforePublish.set(h -> h.changeFeeling("session-2", null));
        assertThat(feeling.service.explanation("owner", "B01", null).status()).isEqualTo("STALE");
        Harness goal = new Harness();
        goal.beforePublish.set(h -> goal.goals.updateAndGet(values -> values.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> 500))));
        assertThat(goal.service.explanation("owner", "A02", null).status()).isEqualTo("STALE");
    }

    @Test
    void httpStoryAndExplanationReachRealStatisticsAndFakeProviderWithAccountAndZone() throws Exception {
        Harness harness = new Harness();
        var mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var owner = new TestingAuthenticationToken("owner", "unused");
        mvc.perform(get("/api/sleep/recent/story").principal(owner).param("timeZone", SELECTED_ZONE.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"))
                .andExpect(header().string("Cache-Control", "no-store"));
        String version = candidate(harness.sent.get(), "A04").fingerprint();
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"A04\",\"evidenceVersion\":\"" + version
                                + "\",\"timeZone\":\"" + SELECTED_ZONE.getId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"))
                .andExpect(jsonPath("$.evidenceFingerprint").value(version));
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"A04\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INELIGIBLE"));
        mvc.perform(get("/api/sleep/recent/story").principal(new TestingAuthenticationToken("other", "unused")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.observations").doesNotExist());
    }

    @Test
    void httpStoryRetriesTransientOnceAndExplanationFallsBackAtItsDeadline() throws Exception {
        Harness harness = new Harness(Duration.ofSeconds(1));
        doThrow(new SleepStoryService.TransientProviderException("temporary"))
                .doAnswer(call -> SleepStoryService.preview(call.getArgument(0))).when(harness.provider).generate(any());
        var mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var owner = new TestingAuthenticationToken("owner", "unused");
        mvc.perform(get("/api/sleep/recent/story").principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        verify(harness.provider, times(2)).generate(any());

        java.util.concurrent.CountDownLatch releaseExplanation = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            try { releaseExplanation.await(); } catch (InterruptedException ignored) { releaseExplanation.await(); }
            return SleepStoryService.explanationPreview(call.getArgument(0));
        }).when(harness.provider).explain(any());
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"));
        releaseExplanation.countDown();
        verify(harness.provider).explain(any());
    }

    private static void assertStoryStale(Consumer<Harness> mutation) {
        Harness harness = new Harness();
        harness.beforePublish.set(mutation);
        var result = harness.service.story("owner", SELECTED_ZONE);
        assertThat(result.status()).isEqualTo("FALLBACK");
    }

    private static int words(String value) { return value.strip().split("\\s+").length; }

    private static SleepStoryService.Candidate candidate(Evidence evidence, String code) {
        return evidence.candidates().stream().filter(item -> code.equals(item.code())).findFirst().orElseThrow();
    }

    private static final class Harness {
        private final AtomicReference<List<MainSleepInsight>> rows = fixtureRows();
        private final AtomicReference<Map<LocalDate, Integer>> goals = new AtomicReference<>(
                rows.get().stream().collect(java.util.stream.Collectors.toMap(MainSleepInsight::day, row -> 400)));
        private final MutableClock clock = new MutableClock(TODAY.atTime(12, 0).atZone(REPORT_ZONE).toInstant(), REPORT_ZONE);
        private final AtomicReference<Consumer<Harness>> beforePublish = new AtomicReference<>(ignored -> {});
        private final AtomicReference<String> storyCode = new AtomicReference<>();
        private final SleepStoryService service;
        private final AtomicReference<Evidence> sent = new AtomicReference<>();
        private final Provider provider;
        private final SleepInsightsStatisticsServiceImpl statistics;

        private Harness() { this(Duration.ofSeconds(20)); }

        private Harness(Duration budget) {
            SleepMainReadQuery source = mock(SleepMainReadQuery.class);
            when(source.readWindow(eq("owner"), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new MainSleepWindow(true, rows.get().stream().filter(row -> in(row.day(), from, to))
                        .map(row -> new MainSleepDay(row.id(), row.day(), row.minutes())).toList());
            });
            when(source.readInsightWindow(eq("owner"), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new MainSleepInsightWindow(true, rows.get().stream().filter(row -> in(row.day(), from, to)).toList());
            });
            when(source.readRangeWindow(eq("owner"), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new MainSleepRangeWindow(true, rangeRows().stream().filter(row -> in(row.day(), from, to)).toList());
            });
            SleepGoalReadQuery goalQuery = mock(SleepGoalReadQuery.class);
            when(goalQuery.readGoalWindow(eq("owner"), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new SleepGoalWindow(true, goals.get().entrySet().stream()
                        .filter(entry -> in(entry.getKey(), from, to))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            });
            statistics = new SleepInsightsStatisticsServiceImpl(source, goalQuery, clock);
            ConsentStore consent = mock(ConsentStore.class);
            when(consent.read("owner")).thenReturn(new Consent(true, "revision-1"));
            when(consent.read("other")).thenReturn(new Consent(false, "revision-0"));
            ObjectProvider<Provider> providers = mock(ObjectProvider.class);
            provider = mock(Provider.class);
            when(providers.getIfAvailable()).thenReturn(provider);
            when(provider.generate(any())).thenAnswer(call -> {
                Evidence original = call.getArgument(0);
                sent.set(original);
                beforePublish.getAndSet(ignored -> {}).accept(this);
                String selected = storyCode.getAndSet(null);
                if (selected == null) return SleepStoryService.preview(original);
                List<SleepStoryService.Candidate> candidates = original.candidates().stream()
                        .filter(item -> "A01".equals(item.code()) || selected.equals(item.code())).toList();
                return SleepStoryService.preview(new Evidence(original.period(), original.selectedZone(), candidates));
            });
            when(provider.explain(any())).thenAnswer(call -> {
                Evidence original = call.getArgument(0);
                beforePublish.getAndSet(ignored -> {}).accept(this);
                return SleepStoryService.explanationPreview(original);
            });
            service = new SleepStoryService(consent, statistics, providers, SleepAiCache.bypass(), clock,
                    budget.toNanos(), budget.toNanos(), Duration.ofMillis(20).toNanos(), System::nanoTime);
        }

        private Evidence capture() { return capture(SELECTED_ZONE); }
        private Evidence capture(ZoneId zone) {
            clock.advance(Duration.ofMinutes(15));
            var response = service.story("owner", zone);
            assertThat(response.status()).isIn("STORY", "FALLBACK");
            return sent.get();
        }

        private void changeFeeling(String id, String feeling) {
            rows.updateAndGet(current -> current.stream().map(row -> row.id().equals(id)
                    ? new MainSleepInsight(row.id(), row.day(), row.minutes(), feeling, row.confirmedAt()) : row).toList());
        }
    }

    private static boolean in(LocalDate day, LocalDate from, LocalDate to) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    private static List<MainSleepRangeDay> rangeRows() {
        ZoneId zone = SELECTED_ZONE;
        List<MainSleepRangeDay> ranges = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDate wakeDay = TODAY.minusDays(i + 1);
            ranges.add(new MainSleepRangeDay("range-" + i, wakeDay, 420, "RANGE", zone.getId(),
                    wakeDay.minusDays(1).atTime(23, 0).atZone(zone).toInstant(),
                    wakeDay.atTime(6, 0).atZone(zone).toInstant()));
        }
        return List.copyOf(ranges);
    }

    private static AtomicReference<List<MainSleepInsight>> fixtureRows() {
        List<MainSleepInsight> rows = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            LocalDate day = TODAY.minusDays(i + 1);
            String feeling = i % 2 == 0 ? "COMFORTABLE" : "TIRED";
            rows.add(new MainSleepInsight("session-" + i, day, 420 + i, feeling,
                    day.atStartOfDay(REPORT_ZONE).toInstant()));
        }
        return new AtomicReference<>(List.copyOf(rows));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;
        private MutableClock(Instant initial, ZoneId zone) { this.instant = new AtomicReference<>(initial); this.zone = zone; }
        void set(Instant value) { instant.set(value); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId requested) { return new MutableClock(instant.get(), requested); }
        @Override public Instant instant() { return instant.get(); }
    }
}
