package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.repository.RedisSleepAiCache;
import com.vitaloom.backend.sleep.service.SleepStoryService.Explanation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Observation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RedisSleepAiCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisSleepAiCache cache = new RedisSleepAiCache(redis, JsonMapper.builder().build());

    @BeforeEach
    void setUp() { when(redis.opsForValue()).thenReturn(values); }

    @Test
    void readsStoryAndExplanationAsTheirTypedValues() {
        var story = new Story("headline", List.of(new Observation("B01", "text", "context", "evidence")));
        var explanation = new Explanation("observation", "basis", "limits", "evidence");
        when(values.get("story-key")).thenReturn(JsonMapper.builder().build().writeValueAsString(story));
        when(values.get("explanation-key")).thenReturn(JsonMapper.builder().build().writeValueAsString(explanation));

        assertThat(cache.readStory("story-key")).isEqualTo(story);
        assertThat(cache.readExplanation("explanation-key")).isEqualTo(explanation);
    }

    @Test
    void writesBothTypesWithTwentyFourHourTtlWithoutOverwritingExistingEntries() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        var story = new Story("headline", List.of());
        var explanation = new Explanation("observation", "basis", "limits", "evidence");

        cache.writeStory("story-key", story);
        cache.writeExplanation("explanation-key", explanation);

        verify(values).setIfAbsent(eq("story-key"), anyString(), eq(Duration.ofHours(24)));
        verify(values).setIfAbsent(eq("explanation-key"), anyString(), eq(Duration.ofHours(24)));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void propagatesRedisAndMalformedValueFailuresForServiceFailSafeHandling() {
        when(values.get("outage")).thenThrow(new IllegalStateException("redis unavailable"));
        when(values.get("corrupt")).thenReturn("not-json");

        assertThatThrownBy(() -> cache.readStory("outage")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.readStory("corrupt")).isInstanceOf(RuntimeException.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(values).setIfAbsent(eq("write-outage"), anyString(), any(Duration.class));
        assertThatThrownBy(() -> cache.writeStory("write-outage", new Story("x", List.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
