package com.vitaloom.backend.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@EnableMongoAuditing(dateTimeProviderRef = "auditingDateTimeProvider", modifyOnCreate = true)
public class ClockConfig {
    @Bean
    public Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    @Bean
    public ZoneId applicationTimeZone(@Value("${app.time-zone}") String timeZone) {
        return ZoneId.of(timeZone);
    }
}
