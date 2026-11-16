package com.vitaloom.backend.sleep.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.vitaloom.backend.sleep.service.SleepStoryService.Candidate;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

// Opt in by class name. The default Surefire patterns do not select *IT.
class GeminiSleepStorySmokeIT {
    @Test
    @Timeout(25)
    void exercisesStoryAndExplanationWithSyntheticEvidence() {
        requireApprovedSandbox();
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            fail("Load the sandbox key into GEMINI_API_KEY through the process secret environment.");
        }
        var provider = new GeminiSleepStoryProvider(key, new ObjectMapper());
        var period = new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7), "Asia/Ho_Chi_Minh");
        var a01 = new Candidate("A01", "synthetic", "synthetic-a01", period,
                Map.of("recordedMainDays", 4), Map.of());
        var b01 = new Candidate("B01", "synthetic", "synthetic-b01", period,
                Map.of("ratedMain", 4, "mainWithoutFeeling", 0),
                Map.of("comfortable", Map.of("count", 2), "normal", Map.of("count", 1),
                        "tired", Map.of("count", 1)));

        var story = provider.generate(new Evidence(period, null, List.of(a01, b01)));
        assertTrue(story.observations().stream().anyMatch(o -> "B01".equals(o.insightCode())));
        var explanation = provider.explain(new Evidence(period, null, List.of(b01)));
        assertEquals(b01.fingerprint(), explanation.evidenceFingerprint());
    }

    private static void requireApprovedSandbox() {
        if (!"true".equalsIgnoreCase(System.getenv("VITALOOM_SLEEP_GEMINI_ENABLED"))
                || !"true".equalsIgnoreCase(System.getenv("VITALOOM_SLEEP_GEMINI_SANDBOX_APPROVED"))) {
            fail("Enable the Gemini smoke only after the isolated sandbox, cost and privacy review are approved.");
        }
    }
}
