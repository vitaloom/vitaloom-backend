package com.vitaloom.backend.statistics.service.impl;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SleepRecentHighlightRanker {
    private static final Comparator<Candidate> ORDER = Comparator
            .comparing(Candidate::freshestDay, Comparator.reverseOrder())
            .thenComparingInt(candidate -> "B01".equals(candidate.code()) ? 0 : 1)
            .thenComparing(Comparator.comparingInt(Candidate::sampleSize).reversed())
            .thenComparing(Candidate::code);

    private SleepRecentHighlightRanker() {}

    static List<Candidate> rank(List<Candidate> candidates) {
        Set<String> seen = new HashSet<>();
        return candidates.stream()
                .filter(candidate -> "READY".equals(candidate.status()))
                .sorted(ORDER)
                .filter(candidate -> seen.add(candidate.code()))
                .limit(3)
                .toList();
    }

    record Candidate(String code, String status, String title, String summary, LocalDate freshestDay, int sampleSize) {}
}
