package com.vitaloom.backend.statistics.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SleepRecentHighlightRankerTest {
    private static final LocalDate LATEST = LocalDate.parse("2026-11-14");

    @Test
    void sameFreshestDayPrefersB01BeforeCoverageA01() {
        var result = SleepRecentHighlightRanker.rank(List.of(
                candidate("A01", LATEST, 5), candidate("B01", LATEST, 3)));

        assertEquals(List.of("B01", "A01"), result.stream().map(SleepRecentHighlightRanker.Candidate::code).toList());
    }

    @Test
    void freshestCoverageDayRanksBeforeEarlierFeelingEvidence() {
        var result = SleepRecentHighlightRanker.rank(List.of(
                candidate("B01", LATEST.minusDays(1), 3), candidate("A01", LATEST, 4)));

        assertEquals(List.of("A01", "B01"), result.stream().map(SleepRecentHighlightRanker.Candidate::code).toList());
    }

    @Test
    void suppressesNonReadyAndDuplicateCandidatesAndCapsAtThree() {
        var result = SleepRecentHighlightRanker.rank(List.of(
                candidate("A01", LATEST, 5), candidate("B01", LATEST, 3),
                new SleepRecentHighlightRanker.Candidate("A02", "INSUFFICIENT_DATA", "", "", LATEST, 4),
                candidate("A03", LATEST.minusDays(1), 4), candidate("B03", LATEST.minusDays(2), 4),
                candidate("B01", LATEST.minusDays(3), 9)));

        assertEquals(List.of("B01", "A01", "A03"),
                result.stream().map(SleepRecentHighlightRanker.Candidate::code).toList());
    }

    private static SleepRecentHighlightRanker.Candidate candidate(String code, LocalDate day, int sample) {
        return new SleepRecentHighlightRanker.Candidate(code, "READY", code, code, day, sample);
    }
}
