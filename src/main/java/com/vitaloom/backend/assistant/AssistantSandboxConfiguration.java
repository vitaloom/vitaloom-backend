package com.vitaloom.backend.assistant;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("!prod & !production")
@Conditional(AssistantActivation.Gemini.class)
class AssistantSandboxConfiguration {
    @Bean
    GeminiAssistantProvider assistantProvider(@Value("${" + AssistantActivation.KEY + "}") String key,
            @Value("${ASSISTANT_GEMINI_CHAT_MODEL:gemini-3.1-flash-lite}") String chatModel) {
        return new GeminiAssistantProvider(key.strip(), chatModel.strip());
    }

    @Bean
    AssistantChatService assistantChatService(MongoTemplate mongo, AssistantProvider provider) {
        return new AssistantChatService(mongo, provider);
    }

    @Bean
    @ConditionalOnProperty(name = "ASSISTANT_RAG_SANDBOX_ENABLED", havingValue = "true")
    AssistantFixtureSearch assistantFixtureSearch(MongoTemplate mongo, GeminiAssistantProvider provider,
            @Value("${ASSISTANT_VECTOR_MONGO_URI:}") String vectorUri,
            @Value("${ASSISTANT_GEMINI_EMBEDDING_MODEL:gemini-embedding-2}") String embeddingModel) {
        if (vectorUri.isBlank()) return new AssistantFixtureSearch(mongo, provider, null, embeddingModel.strip());
        var connection = new ConnectionString(vectorUri.strip());
        var database = connection.getDatabase();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("ASSISTANT_VECTOR_MONGO_URI requires a database name");
        }
        var client = MongoClients.create(connection);
        try {
            return new AssistantFixtureSearch(new MongoTemplate(client, database), provider, client, embeddingModel.strip());
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "ASSISTANT_RAG_INGEST", havingValue = "true")
    ApplicationRunner assistantFixtureIngest(ObjectProvider<AssistantFixtureSearch> fixtureSearch) {
        return args -> {
            var logger = LoggerFactory.getLogger(AssistantSandboxConfiguration.class);
            var search = fixtureSearch.getIfAvailable();
            if (search == null) {
                logger.warn("Assistant FAQ ingest requires the RAG sandbox to be enabled");
                return;
            }
            try {
                search.ingest();
            } catch (RuntimeException failure) {
                logger.warn("Assistant FAQ ingest failed. Check sandbox embedding and MongoDB availability.");
            }
        };
    }
}
