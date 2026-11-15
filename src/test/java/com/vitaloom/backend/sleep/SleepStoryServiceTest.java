package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.service.DeterministicSleepStoryProvider;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.Observation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Insights;
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
        when(insights.insights("owner")).thenReturn(snapshot(item("A01", "a")));
        assertThat(service.story("owner").status()).isEqualTo("INELIGIBLE");
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsFabricatedClaimAndFallsBackToCurrentEvidence() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"));
        when(insights.insights("owner")).thenReturn(snapshot(item("B01", "current")));
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
        when(insights.insights("owner")).thenReturn(
                snapshot(item("A01", "before"), item("B01", "b")),
                snapshot(item("A01", "after"), item("B01", "b")));
        when(provider.generate(any())).thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        assertThat(service.story("owner").status()).isEqualTo("FALLBACK");
        verify(provider, times(1)).generate(any());
    }

    @Test
    void consentChangedDuringGenerationSuppressesAllOutput() {
        when(consent.read("owner")).thenReturn(new Consent(true, "on"), new Consent(true, "re-enabled"));
        when(insights.insights("owner")).thenReturn(snapshot(item("B01", "current")));
        when(provider.generate(any())).thenAnswer(invocation -> SleepStoryService.preview(invocation.getArgument(0)));
        var response = service.story("owner");
        assertThat(response.status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(response.observations()).isNull();
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
        return new Insights(new Period(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15),
                "Asia/Ho_Chi_Minh"), List.of(items));
    }

    private static Item item(String code, String fingerprint) {
        return new Item(code, code.substring(0, 1), "READY", "", true, false, "",
                Map.of("ratedMain", 3, "mainWithoutFeeling", 1, "recordedMainDays", 4),
                Map.of("comfortable", Map.of("count", 1L), "normal", Map.of("count", 1L),
                        "tired", Map.of("count", 1L)),
                List.of("private-source-id"), List.of(), "p2", fingerprint);
    }
}
