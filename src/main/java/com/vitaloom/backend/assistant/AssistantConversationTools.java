package com.vitaloom.backend.assistant;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

final class AssistantConversationTools {
    private final AssistantFixtureSearch faq;
    private final UUID turnId;
    private final Map<String, AssistantConversationReply.EvidenceSource> sources = new LinkedHashMap<>();
    private final Set<AssistantCourseAnswers.Source> knowledgeSources = new HashSet<>();
    private boolean knowledgeRequested;
    private boolean healthKnowledgeRequested;
    private boolean knowledgeUnavailable;
    private boolean personalDenied;

    AssistantConversationTools(AssistantFixtureSearch faq, UUID turnId) {
        this.faq = faq;
        this.turnId = turnId;
    }

    @Tool(description = "Search approved VitaLoom health education and application information. Supply a short, "
            + "standalone knowledge question in the user's language without names, record dates or personal values. "
            + "Results contain vetted excerpts and evidence keys, and may contain no match.")
    public Knowledge searchApprovedKnowledge(@ToolParam(description = "Public knowledge query") String query,
            @ToolParam(description = "True for health education, recommendations, or a health comparison. "
                    + "False for application capabilities or metric definitions.") boolean healthEducation) {
        knowledgeRequested = true;
        healthKnowledgeRequested |= healthEducation;
        if (query == null || query.isBlank() || query.length() > 300) {
            return new Knowledge("INVALID_ARGUMENTS", List.of());
        }
        if (faq == null) {
            knowledgeUnavailable = true;
            return new Knowledge("UNAVAILABLE", List.of());
        }
        try {
            var matches = faq.retrieveReadyEvidence(query.strip(), turnId).stream()
                    .filter(item -> !healthEducation || item.healthEducation()).map(item -> {
                var facts = item.evidence();
                if (item.healthEducation()) knowledgeSources.add(item.source());
                return add(item.source(), Map.of("question", facts.question(), "answer", facts.answer()),
                        item::current);
            }).toList();
            return new Knowledge(matches.isEmpty() ? "NO_MATCH" : "FOUND", matches);
        } catch (RuntimeException failure) {
            knowledgeUnavailable = true;
            LoggerFactory.getLogger(AssistantConversationTools.class).atWarn().addKeyValue("turn_id", turnId)
                    .addKeyValue("error_type", failure.getClass().getSimpleName()).log("Assistant knowledge unavailable");
            return new Knowledge("UNAVAILABLE", List.of());
        }
    }

    @Tool(description = "Resolve instructions for a supported application screen without opening it or writing records.")
    public Evidence resolveAppNavigation(
            @ToolParam(description = "Screen identifier: SLEEP_JOURNAL, SLEEP_RECENT, WATER_PROGRESS or WATER_LOG")
            Destination destination) {
        var entry = switch (destination) {
            case SLEEP_JOURNAL -> AssistantFixture.JOURNAL;
            case WATER_PROGRESS -> AssistantFixture.WATER_PROGRESS;
            case WATER_LOG -> AssistantFixture.WATER_LOG;
            case SLEEP_RECENT -> null;
        };
        if (entry != null) return add(entry.source(), Map.of("instructions", entry.answer()), () -> true);
        return add(new AssistantCourseAnswers.Source("app-help-sleep-recent", 1, "Giấc ngủ gần đây",
                "/sleep/recent", null, null), Map.of("instructions", "Mở Giấc ngủ rồi chọn Giấc ngủ gần đây."),
                () -> true);
    }

    Evidence add(AssistantCourseAnswers.Source source, Object facts, BooleanSupplier current) {
        String key = "e" + (sources.size() + 1);
        sources.put(key, new AssistantConversationReply.EvidenceSource(source, current));
        return new Evidence(key, facts);
    }

    Map<String, AssistantConversationReply.EvidenceSource> sources() {
        return Map.copyOf(sources);
    }

    boolean knowledgeRequested() { return knowledgeRequested; }

    boolean healthKnowledgeRequested() { return healthKnowledgeRequested; }

    boolean knowledgeUnavailable() { return knowledgeUnavailable; }

    boolean supportsKnowledge(AssistantCourseAnswers.Source source) { return knowledgeSources.contains(source); }

    void markPersonalDenied() { personalDenied = true; }

    boolean personalDenied() { return personalDenied; }

    public enum Destination { SLEEP_JOURNAL, SLEEP_RECENT, WATER_PROGRESS, WATER_LOG }

    public record Evidence(String key, Object facts) {}
    public record Knowledge(String status, List<Evidence> results) {}
}
