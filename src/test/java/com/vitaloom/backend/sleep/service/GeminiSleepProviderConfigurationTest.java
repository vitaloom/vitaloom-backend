package com.vitaloom.backend.sleep.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.ObjectMapper;

class GeminiSleepProviderConfigurationTest {
    private ApplicationContextRunner providers() {
        return new ApplicationContextRunner()
                .withUserConfiguration(GeminiSleepStoryProvider.class, DeterministicSleepStoryProvider.class)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    private ApplicationContextRunner defaultProfile() {
        return providers().withInitializer(context -> context.getEnvironment().setActiveProfiles("default"));
    }

    @Test
    void defaultProfileActivatesGeminiWhenEnabledIsOmittedAndKeyIsProvided() {
        defaultProfile().withPropertyValues("GEMINI_API_KEY=synthetic-test-value")
                .run(context -> {
                    assertThat(context).hasSingleBean(GeminiSleepStoryProvider.class);
                    assertThat(context).doesNotHaveBean(DeterministicSleepStoryProvider.class);
                });
    }

    @Test
    void explicitFalseDisablesGeminiAndMissingKeyFailsStartup() {
        defaultProfile().withPropertyValues("vitaloom.sleep.gemini.enabled=false", "GEMINI_API_KEY=synthetic-test-value")
                .run(context -> assertThat(context).doesNotHaveBean(GeminiSleepStoryProvider.class));
        defaultProfile().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Gemini provider requires a valid GEMINI_API_KEY.");
        });
    }

    @Test
    void documentedEnvironmentNamesActivateGeminiOnDefaultProfile() {
        providers().withInitializer(context -> {
                    var environment = context.getEnvironment();
                    environment.setActiveProfiles("default");
                    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("synthetic-env", Map.of(
                            "VITALOOM_SLEEP_GEMINI_ENABLED", "true",
                            "GEMINI_API_KEY", "synthetic-test-value")));
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(GeminiSleepStoryProvider.class);
                    assertThat(context).doesNotHaveBean(DeterministicSleepStoryProvider.class);
                });
    }

    @Test
    void onlyTestProfileKeepsTheFakeAndProductionExcludesGemini() {
        providers().withPropertyValues("spring.profiles.active=dev", "vitaloom.sleep.story.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DeterministicSleepStoryProvider.class);
                    assertThat(context).doesNotHaveBean(GeminiSleepStoryProvider.class);
                });
        providers().withPropertyValues("spring.profiles.active=test", "vitaloom.sleep.story.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DeterministicSleepStoryProvider.class));
        providers().withPropertyValues("spring.profiles.active=prod", "GEMINI_API_KEY=synthetic-test-value")
                .run(context -> assertThat(context).doesNotHaveBean(GeminiSleepStoryProvider.class));
    }
}
