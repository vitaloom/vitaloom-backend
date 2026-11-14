package com.vitaloom.backend.support;

import com.mongodb.ConnectionString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;

@TestConfiguration(proxyBeanMethods = false)
public class MongoTestcontainersConfiguration {
    @Bean
    MongoConnectionDetails mongoConnectionDetails(@Value("${vitaloom.test.mongodb.database}") String database) {
        ConnectionString connectionString = new ConnectionString(SharedMongo.CONTAINER.getReplicaSetUrl(database));
        return () -> connectionString;
    }

    // Keep MongoDB alive across cached Spring contexts. Testcontainers cleans it up when the test JVM exits.
    private static final class SharedMongo {
        private static final MongoDBContainer CONTAINER = start();

        private static MongoDBContainer start() {
            MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.29-noble").withReplicaSet();
            mongo.start();
            return mongo;
        }
    }
}
