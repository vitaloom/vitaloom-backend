package com.vitaloom.backend.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantReadyFaqConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantReadyFaqConfiguration.class, ProviderConfiguration.class);

    @Test
    void readySearchActivatesWithKeyAndVectorUriOutsideDefaultProfile() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=staging",
                "ASSISTANT_GEMINI_API_KEY=test-only-key",
                "ASSISTANT_VECTOR_MONGO_URI=mongodb://localhost/assistant_vector")
                .run(context -> assertThat(context).hasSingleBean(AssistantFixtureSearch.class));
    }

    @Test
    void readySearchRequiresBothNamedSettings() {
        contextRunner.withPropertyValues("ASSISTANT_GEMINI_API_KEY=test-only-key")
                .run(context -> assertThat(context).doesNotHaveBean(AssistantFixtureSearch.class));
        contextRunner.withPropertyValues("ASSISTANT_VECTOR_MONGO_URI=mongodb://localhost/assistant_vector")
                .run(context -> assertThat(context).doesNotHaveBean(AssistantFixtureSearch.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(AssistantActivation.Gemini.class)
    static class ProviderConfiguration {
        @Bean
        GeminiAssistantProvider assistantProvider(@Value("${ASSISTANT_GEMINI_API_KEY}") String key) {
            return new GeminiAssistantProvider(key, "gemini-3.1-flash-lite");
        }
    }
}
