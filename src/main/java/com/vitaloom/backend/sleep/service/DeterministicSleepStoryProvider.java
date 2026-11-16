package com.vitaloom.backend.sleep.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("(dev | test) & !prod & !production & !sleep-ai-gemini-sandbox")
@ConditionalOnProperty(name = "vitaloom.sleep.story.enabled", havingValue = "true")
public class DeterministicSleepStoryProvider implements SleepStoryService.Provider {
    @Override
    public SleepStoryService.Story generate(SleepStoryService.Evidence evidence) {
        return SleepStoryService.preview(evidence);
    }

    @Override
    public SleepStoryService.Explanation explain(SleepStoryService.Evidence evidence) {
        return SleepStoryService.explanationPreview(evidence);
    }
}
