package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.service.WaterService;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration(proxyBeanMethods = false)
@Conditional({AssistantActivation.Course.class, AssistantActivation.Gemini.class})
@ConditionalOnProperty(name = "ASSISTANT_VOICE_ENABLED", havingValue = "true")
class AssistantVoiceConfiguration {
    @Bean
    AssistantSpeechToText assistantSpeechToText(
            @Value("${ASSEMBLYAI_API_KEY}") String apiKey) {
        return new AssemblyAiAssistantSpeechToText(apiKey, URI.create("https://api.assemblyai.com"));
    }

    @Bean
    AssistantVoiceTurns assistantVoiceTurns(MongoTemplate mongo, Clock clock, GeminiAssistantProvider gemini,
            WaterService water, SleepJournalService sleep, AssistantSpeechToText stt) {
        return new AssistantVoiceTurns(mongo, clock, stt, gemini::selectVoiceTool,
                new AssistantVoiceActions(water, sleep));
    }
}
