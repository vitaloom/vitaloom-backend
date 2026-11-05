package com.vitaloom.backend.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MongoClientSettingsConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoServerSelectionTimeout(
            @Value("${vitaloom.mongodb.server-selection-timeout-ms:2000}") long timeoutMs) {
        return builder -> builder.applyToClusterSettings(
                cluster -> cluster.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS));
    }
}
