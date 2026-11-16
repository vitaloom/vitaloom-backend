package com.vitaloom.backend.sleep.repository;

import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.service.SleepStoryService.Explanation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RedisSleepAiCache implements SleepAiCache {
    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisSleepAiCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Story readStory(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? null : json.readValue(value, Story.class);
    }

    @Override
    public Explanation readExplanation(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? null : json.readValue(value, Explanation.class);
    }

    @Override
    public void writeStory(String key, Story value) {
        redis.opsForValue().setIfAbsent(key, json.writeValueAsString(value), TTL);
    }

    @Override
    public void writeExplanation(String key, Explanation value) {
        redis.opsForValue().setIfAbsent(key, json.writeValueAsString(value), TTL);
    }
}
