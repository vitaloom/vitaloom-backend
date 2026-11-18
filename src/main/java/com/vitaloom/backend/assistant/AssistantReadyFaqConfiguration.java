package com.vitaloom.backend.assistant;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration(proxyBeanMethods = false)
@Conditional(AssistantActivation.Gemini.class)
class AssistantReadyFaqConfiguration {
    @Bean("assistantReadyFaqSearch")
    @Conditional(AssistantActivation.VectorMongo.class)
    AssistantFixtureSearch assistantReadyFaqSearch(GeminiAssistantProvider provider,
            @Value("${ASSISTANT_VECTOR_MONGO_URI}") String vectorUri,
            @Value("${ASSISTANT_GEMINI_EMBEDDING_MODEL:gemini-embedding-2}") String embeddingModel) {
        var connection = new ConnectionString(vectorUri.strip());
        var database = connection.getDatabase();
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("ASSISTANT_VECTOR_MONGO_URI requires a database name");
        }
        var client = MongoClients.create(connection);
        try {
            return new AssistantFixtureSearch(new MongoTemplate(client, database), provider, client,
                    embeddingModel.strip());
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }
}
