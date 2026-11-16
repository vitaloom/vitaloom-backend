package com.vitaloom.backend.sleep.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.ObjectMapper;

class GeminiSleepProviderConfigurationTest {
    @Test
    void documentedEnvironmentNamesActivateOnlyGeminiInSandbox() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeminiSleepStoryProvider.class, DeterministicSleepStoryProvider.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withInitializer(context -> {
                    var environment = context.getEnvironment();
                    environment.setActiveProfiles("dev", "sleep-ai-gemini-sandbox");
                    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("synthetic-env", Map.of(
                            "VITALOOM_SLEEP_GEMINI_ENABLED", "true",
                            "VITALOOM_SLEEP_GEMINI_SANDBOX_APPROVED", "true",
                            "VITALOOM_SLEEP_STORY_ENABLED", "true",
                            "GEMINI_API_KEY", "synthetic-test-value")));
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(GeminiSleepStoryProvider.class);
                    assertThat(context).doesNotHaveBean(DeterministicSleepStoryProvider.class);
                });
    }
}
