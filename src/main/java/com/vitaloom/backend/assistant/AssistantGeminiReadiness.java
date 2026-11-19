package com.vitaloom.backend.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class AssistantGeminiReadiness {
    private final boolean verified;

    AssistantGeminiReadiness(@Value("${ASSISTANT_GEMINI_HEALTH_PROCESSING_VERIFIED:false}") boolean verified) {
        this.verified = verified;
    }

    void requireReady() {
        if (!verified) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Assistant provider configuration is unavailable");
    }
}
