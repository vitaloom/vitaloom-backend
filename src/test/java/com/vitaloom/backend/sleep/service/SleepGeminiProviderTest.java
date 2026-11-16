package com.vitaloom.backend.sleep.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitaloom.backend.sleep.service.GeminiSleepStoryProvider;
import com.vitaloom.backend.sleep.service.GeminiSleepStoryProvider.ProviderResponse;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Candidate;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.controller.SleepStoryController;
import com.vitaloom.backend.sleep.controller.exceptionhandler.SleepExceptionHandler;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SleepGeminiProviderTest {
    private static final String KEY = "test-gemini-key-with-synthetic-content";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void postsAllowlistedEvidenceAndReturnsOnlyApprovedStorySentences() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
            calls.incrementAndGet();
            assertThat(request.uri().toString()).isEqualTo(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");
            assertThat(request.headers().firstValue("x-goog-api-key")).hasValue(KEY);
            var body = mapper.readTree(requestBody);
            assertThat(body.at("/generationConfig/responseFormat/text/mimeType").asText()).isEqualTo("APPLICATION_JSON");
            assertThat(body.at("/generationConfig/responseFormat/text/schema/properties/insightCodes").isObject()).isTrue();
            String prompt = body.at("/contents/0/parts/0/text").asText();
            assertThat(prompt).doesNotContain("privateText", "fingerprint", "2026-11", "Asia/Ho_Chi_Minh");
            String input = prompt.substring(prompt.indexOf("JSON input: ") + "JSON input: ".length());
            var candidates = mapper.readTree(input).get("readyEvidence");
            assertThat(candidates.at("/A01/recordedMainDays").intValue()).isEqualTo(6);
            assertThat(candidates.at("/B01/comfortable/count").intValue()).isEqualTo(1);
            assertThat(candidates.at("/B01/normal/count").intValue()).isEqualTo(2);
            assertThat(candidates.at("/B01/tired/count").intValue()).isEqualTo(3);
            return response("{\"insightCodes\":[\"B01\"]}");
        }, new AtomicLong()::get);

        var b01 = evidence().candidates().getFirst();
        var a01 = new Candidate("A01", "policy-v1", "a01-fingerprint", b01.period(),
                Map.of("recordedMainDays", 6), Map.of());
        var result = provider.generate(new Evidence(b01.period(), "Asia/Ho_Chi_Minh", List.of(b01, a01)));

        assertThat(calls).hasValue(1);
        assertThat(result.observations()).containsExactly(SleepStoryService.approvedObservation(b01));
        assertThat(provider.cacheNamespace()).contains("gemini-3.1-flash-lite");
    }

    @Test
    void refusesUnsupportedOutputBeforePublishing() {
        AtomicInteger calls = new AtomicInteger();
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
            calls.incrementAndGet();
            return response("{\"insightCodes\":[\"B01\",\"B01\"]}");
        }, System::nanoTime);

        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsDuplicateTrailingUnknownAndIncompleteStructuredResponsesWithoutRetry() {
        for (String structured : List.of(
                "{\"insightCodes\":[\"B01\"],\"insightCodes\":[\"B01\"]}",
                "{\"insightCodes\":[\"B01\"]} {}",
                "{\"insightCodes\":[\"B01\"],\"extra\":true}")) {
            AtomicInteger calls = new AtomicInteger();
            GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
                calls.incrementAndGet();
                return response(structured);
            }, System::nanoTime);
            assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(RuntimeException.class);
            assertThat(calls).hasValue(1);
        }

        AtomicInteger calls = new AtomicInteger();
        GeminiSleepStoryProvider incomplete = provider((request, requestBody) -> {
            calls.incrementAndGet();
            return response("MAX_TOKENS", "{\"insightCodes\":[\"B01\"]}");
        }, System::nanoTime);
        assertThatThrownBy(() -> incomplete.generate(evidence())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void reservesEveryCallAgainstRollingAndLifetimeCaps() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong ticker = new AtomicLong();
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
            calls.incrementAndGet();
            return response("{\"insightCodes\":[\"B01\"]}");
        }, ticker::get);
        for (int i = 0; i < 4; i++) provider.generate(evidence());

        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(4);

        long minute = java.time.Duration.ofMinutes(1).toNanos();
        ticker.set(minute - 1);
        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalStateException.class);
        for (int window = 1; window <= 2; window++) {
            ticker.set(window * minute);
            for (int i = 0; i < 4; i++) provider.generate(evidence());
        }
        ticker.set(3 * minute);
        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
        assertThat(calls).hasValue(12);
    }

    @Test
    void explanationAcceptsOnlyExactInsightAndApprovedFraming() {
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
            var config = mapper.readTree(requestBody).path("generationConfig");
            var allowedCodes = config.at("/responseFormat/text/schema/properties/insightCode/enum");
            assertThat(allowedCodes.size()).isEqualTo(1);
            assertThat(allowedCodes.get(0).asText()).isEqualTo("B01");
            return response("{\"insightCode\":\"B01\",\"framing\":\"GENTLE\"}");
        }, System::nanoTime);
        var explanation = provider.explain(evidence());
        assertThat(explanation.observation()).startsWith("Bạn có thể nhìn nhận rằng:");
        assertThat(explanation.evidenceFingerprint()).isEqualTo("fingerprint");
        GeminiSleepStoryProvider unexpectedField = provider((request, requestBody) -> response(
                "{\"insightCode\":\"B01\",\"framing\":\"DIRECT\",\"claim\":\"unexpected\"}"), System::nanoTime);
        assertThatThrownBy(() -> unexpectedField.explain(evidence())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sandboxCanLowerItsLifetimeDispatchLimit() {
        AtomicInteger calls = new AtomicInteger();
        GeminiSleepStoryProvider provider = new GeminiSleepStoryProvider(KEY, mapper, (request, requestBody) -> {
            calls.incrementAndGet();
            return response("{\"insightCodes\":[\"B01\"]}");
        }, System::nanoTime, 1);

        provider.generate(evidence());
        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsMalformedKeyAndApiErrorsWithoutRetryClassification() {
        assertThatThrownBy(() -> new GeminiSleepStoryProvider("bad key", mapper,
                (request, requestBody) -> response("{}"), System::nanoTime)).isInstanceOf(IllegalStateException.class);
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> new ProviderResponse(429, new byte[0]), System::nanoTime);
        assertThatThrownBy(() -> provider.generate(evidence())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerActivationRequiresSandboxProfileBothSwitchesAndKey() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(GeminiSleepStoryProvider.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("spring.profiles.active=sleep-ai-gemini-sandbox");
        context.run(result -> assertThat(result).doesNotHaveBean(GeminiSleepStoryProvider.class));
        context.withPropertyValues("vitaloom.sleep.gemini.enabled=true")
                .run(result -> assertThat(result).doesNotHaveBean(GeminiSleepStoryProvider.class));
        context.withPropertyValues("vitaloom.sleep.gemini.enabled=true",
                        "vitaloom.sleep.gemini.sandbox-approved=true")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(result.getStartupFailure()).hasRootCauseMessage(
                            "Gemini sandbox requires a valid GEMINI_API_KEY.");
                });
        context.withPropertyValues("vitaloom.sleep.gemini.enabled=true",
                        "vitaloom.sleep.gemini.sandbox-approved=true", "GEMINI_API_KEY=" + KEY)
                .run(result -> assertThat(result).hasSingleBean(GeminiSleepStoryProvider.class));
        context.withPropertyValues("spring.profiles.active=sleep-ai-gemini-sandbox,prod",
                        "vitaloom.sleep.gemini.enabled=true", "vitaloom.sleep.gemini.sandbox-approved=true",
                        "GEMINI_API_KEY=" + KEY)
                .run(result -> assertThat(result).doesNotHaveBean(GeminiSleepStoryProvider.class));
    }

    @Test
    void mockMvcStoryAndExplanationUseProviderAndCheckConsentBeforeDispatch() throws Exception {
        var consent = mock(SleepStoryService.ConsentStore.class);
        var insights = mock(SleepInsightsStatisticsService.class);
        AtomicBoolean ownerEnabled = new AtomicBoolean(true);
        when(consent.read("owner")).thenAnswer(call -> new SleepStoryService.Consent(ownerEnabled.get(),
                ownerEnabled.get() ? "enabled" : "withdrawn"));
        when(insights.fullInsights("owner", null)).thenReturn(new SleepInsightsStatisticsService.Insights(
                evidence().period(), List.of(item()), List.of()));
        when(insights.detail("owner", "B01")).thenReturn(detail());
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean withdrawAfterDispatch = new AtomicBoolean();
        AtomicBoolean returnUnknownSelection = new AtomicBoolean();
        AtomicBoolean afterEnabled = new AtomicBoolean(true);
        GeminiSleepStoryProvider provider = provider((request, requestBody) -> {
            requests.incrementAndGet();
            String prompt = new String(requestBody, java.nio.charset.StandardCharsets.UTF_8);
            if (withdrawAfterDispatch.get()) afterEnabled.set(false);
            return response(prompt.contains("framing")
                    ? "{\"insightCode\":\"B01\",\"framing\":\"DIRECT\"}"
                    : returnUnknownSelection.get()
                            ? "{\"insightCodes\":[\"C01\"]}" : "{\"insightCodes\":[\"B01\"]}");
        }, System::nanoTime);
        var lookup = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(lookup.getIfAvailable()).thenReturn(provider);
        var service = new SleepStoryService(consent, insights, lookup, SleepAiCache.bypass(), Clock.systemUTC());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var owner = new TestingAuthenticationToken("owner", "unused");

        mvc.perform(get("/api/sleep/recent/story").principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"))
                .andExpect(jsonPath("$.observations[0].insightCode").value("B01"));
        mvc.perform(post("/api/sleep/ai/explanations").principal(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        assertThat(requests).hasValue(2);

        when(consent.read("after")).thenAnswer(call -> new SleepStoryService.Consent(afterEnabled.get(),
                afterEnabled.get() ? "enabled" : "withdrawn"));
        when(insights.fullInsights("after", null)).thenReturn(new SleepInsightsStatisticsService.Insights(
                evidence().period(), List.of(item()), List.of()));
        SleepAiCache afterCache = mock(SleepAiCache.class);
        var afterLookup = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(afterLookup.getIfAvailable()).thenReturn(provider);
        var afterService = new SleepStoryService(consent, insights, afterLookup, afterCache, Clock.systemUTC());
        MockMvc afterMvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(afterService))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        withdrawAfterDispatch.set(true);
        afterMvc.perform(get("/api/sleep/recent/story")
                        .principal(new TestingAuthenticationToken("after", "unused")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"));
        assertThat(requests).hasValue(3);
        verify(afterCache, never()).writeStory(any(), any());
        verify(afterCache, never()).writeStory(any(), any(), any());

        ownerEnabled.set(true);
        withdrawAfterDispatch.set(false);

        when(consent.read("invalid")).thenReturn(new SleepStoryService.Consent(true, "enabled"));
        when(insights.fullInsights("invalid", null)).thenReturn(new SleepInsightsStatisticsService.Insights(
                evidence().period(), List.of(item()), List.of()));
        SleepAiCache invalidCache = mock(SleepAiCache.class);
        var invalidLookup = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(invalidLookup.getIfAvailable()).thenReturn(provider);
        var invalidService = new SleepStoryService(consent, insights, invalidLookup, invalidCache, Clock.systemUTC());
        MockMvc invalidMvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(invalidService))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        returnUnknownSelection.set(true);
        invalidMvc.perform(get("/api/sleep/recent/story")
                        .principal(new TestingAuthenticationToken("invalid", "unused")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"));
        assertThat(requests).hasValue(4);
        verify(invalidCache, never()).writeStory(any(), any());
        verify(invalidCache, never()).writeStory(any(), any(), any());
        returnUnknownSelection.set(false);

        AtomicBoolean enabled = new AtomicBoolean(true);
        when(consent.read("revoked")).thenAnswer(call ->
                new SleepStoryService.Consent(enabled.get(), enabled.get() ? "enabled" : "withdrawn"));
        when(insights.fullInsights("revoked", null)).thenAnswer(call -> {
            enabled.set(false);
            return new SleepInsightsStatisticsService.Insights(evidence().period(), List.of(item()), List.of());
        });
        var revokedLookup = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(revokedLookup.getIfAvailable()).thenReturn(provider);
        var revokedService = new SleepStoryService(consent, insights, revokedLookup, SleepAiCache.bypass(), Clock.systemUTC());
        MockMvc revokedMvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(revokedService))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        revokedMvc.perform(get("/api/sleep/recent/story").principal(new TestingAuthenticationToken("revoked", "unused")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"));
        assertThat(requests).hasValue(4);
    }

    private GeminiSleepStoryProvider provider(GeminiSleepStoryProvider.Transport transport,
            java.util.function.LongSupplier ticker) {
        return new GeminiSleepStoryProvider(KEY, mapper, transport, ticker);
    }

    private ProviderResponse response(String structured) {
        return response("STOP", structured);
    }

    private ProviderResponse response(String finishReason, String structured) {
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("candidates", List.of(Map.of(
                    "finishReason", finishReason, "content", Map.of("parts", List.of(Map.of("text", structured)))))));
            return new ProviderResponse(200, body);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Evidence evidence() {
        var period = new com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07"), "Asia/Ho_Chi_Minh");
        var candidate = new Candidate("B01", "policy-v1", "fingerprint", period,
                Map.of("ratedMain", 6, "mainWithoutFeeling", 0), Map.of("comfortable", Map.of("count", 1),
                        "normal", Map.of("count", 2), "tired", Map.of("count", 3),
                        "privateText", "must never be sent"));
        return new Evidence(period, "Asia/Ho_Chi_Minh", List.of(candidate));
    }

    private static SleepInsightsStatisticsService.Item item() {
        var candidate = evidence().candidates().getFirst();
        return new SleepInsightsStatisticsService.Item(candidate.code(), "B", "READY", "B01", true, false,
                "summary", candidate.sample(), candidate.evidence(), List.of(), List.of(), "policy-v1", "fingerprint");
    }

    private static SleepInsightsStatisticsService.Detail detail() {
        var candidate = evidence().candidates().getFirst();
        return new SleepInsightsStatisticsService.Detail(candidate.code(), "B", "READY", candidate.period(), "B01",
                "summary", candidate.sample(), candidate.evidence(), List.of(), List.of(), "policy-v1", "fingerprint");
    }
}
