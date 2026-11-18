package com.vitaloom.backend.assistant;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

final class AssistantActivation {
    static final String KEY = "ASSISTANT_GEMINI_API_KEY";

    private AssistantActivation() {}

    static final class Gemini implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty(KEY));
        }
    }

    static final class Course implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            var enabled = environment.getProperty("vitaloom.assistant.course.enabled", Boolean.class);
            return enabled != null ? enabled : StringUtils.hasText(environment.getProperty(KEY));
        }
    }
}
