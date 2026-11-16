package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.sleep.service.SleepStoryService.Explanation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;

/** Stores validated Sleep AI output without owning cache-key freshness policy. */
public interface SleepAiCache {
    Story readStory(String key);

    Explanation readExplanation(String key);

    void writeStory(String key, Story value);

    void writeExplanation(String key, Explanation value);

    static SleepAiCache bypass() {
        return new SleepAiCache() {
            @Override public Story readStory(String key) { return null; }
            @Override public Explanation readExplanation(String key) { return null; }
            @Override public void writeStory(String key, Story value) {}
            @Override public void writeExplanation(String key, Explanation value) {}
        };
    }
}
