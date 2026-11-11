package com.vitaloom.backend.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    public Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    public ZoneId applicationTimeZone(@Value("${app.time-zone}") String timeZone) {
        return ZoneId.of(timeZone);
    }
}
