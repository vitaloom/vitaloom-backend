package com.vitaloom.backend.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

final class AssistantConversationTools {
    private final AssistantFixtureSearch faq;
    private final UUID turnId;
    private final BooleanSupplier current;
    private final Map<String, CollectedEvidence> sources = new LinkedHashMap<>();
    private boolean knowledgeRequested;
    private boolean healthKnowledgeRequested;
    private boolean knowledgeUnavailable;
    private boolean personalDenied;
    private boolean personalUnavailable;

    AssistantConversationTools(AssistantFixtureSearch faq, UUID turnId, BooleanSupplier current) {
        this.faq = faq;
        this.turnId = turnId;
        this.current = current;
    }

    @Tool(description = "Search approved VitaLoom health education and application information. Supply a short, "
            + "standalone knowledge question in the user's language without names, record dates or personal values. "
            + "Results contain vetted excerpts and evidence keys, and may contain no match.")
    public Knowledge searchApprovedKnowledge(@ToolParam(description = "Public knowledge query") String query,
            @ToolParam(description = "True for health education, recommendations, or a health comparison. "
                    + "False for application capabilities or metric definitions.") boolean healthEducation) {
        AssistantNativeChat.requireCurrent(current);
        knowledgeRequested = true;
        healthKnowledgeRequested |= healthEducation;
        if (query == null || query.isBlank() || query.length() > 300
                || query.contains("[account identifier omitted]") || query.contains("[access token omitted]")) {
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
                return add(item.healthEducation() ? EvidenceKind.HEALTH_EDUCATION : EvidenceKind.APP,
                        item.source(), Map.of("question", facts.question(), "answer", facts.answer()),
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
        AssistantNativeChat.requireCurrent(current);
        var entry = switch (destination) {
            case SLEEP_JOURNAL -> AssistantFixture.JOURNAL;
            case WATER_PROGRESS -> AssistantFixture.WATER_PROGRESS;
            case WATER_LOG -> AssistantFixture.WATER_LOG;
            case SLEEP_RECENT -> null;
        };
        if (entry != null) return add(EvidenceKind.APP, entry.source(), Map.of("instructions", entry.answer()), () -> true);
        return add(EvidenceKind.APP, new AssistantCourseAnswers.Source("app-help-sleep-recent", 1, "Giấc ngủ gần đây",
                "/sleep/recent", null, null), Map.of("instructions", "Mở Giấc ngủ rồi chọn Giấc ngủ gần đây."),
                () -> true);
    }

    Evidence addPersonal(String status, AssistantCourseAnswers.Source source, Object facts, BooleanSupplier current) {
        String key = collect(EvidenceKind.PERSONAL, source, Map.of("status", status, "facts", facts), current);
        return new Evidence(key, facts);
    }

    private Evidence add(EvidenceKind kind, AssistantCourseAnswers.Source source, Object facts, BooleanSupplier current) {
        return new Evidence(collect(kind, source, facts, current), facts);
    }

    private String collect(EvidenceKind kind, AssistantCourseAnswers.Source source, Object facts, BooleanSupplier current) {
        String key = "e" + (sources.size() + 1);
        sources.put(key, new CollectedEvidence(source, current, facts, kind));
        return key;
    }

    Map<String, CollectedEvidence> sources() {
        return Map.copyOf(sources);
    }

    boolean knowledgeRequested() { return knowledgeRequested; }

    boolean healthKnowledgeRequested() { return healthKnowledgeRequested; }

    boolean knowledgeUnavailable() { return knowledgeUnavailable; }

    void markPersonalDenied() { personalDenied = true; }

    boolean personalDenied() { return personalDenied; }

    void markPersonalUnavailable() { personalUnavailable = true; }

    boolean personalUnavailable() { return personalUnavailable; }

    public enum Destination { SLEEP_JOURNAL, SLEEP_RECENT, WATER_PROGRESS, WATER_LOG }

    enum EvidenceKind { PERSONAL, APP, HEALTH_EDUCATION }

    record CollectedEvidence(AssistantCourseAnswers.Source source, BooleanSupplier current, Object facts, EvidenceKind kind) {}

    public record Evidence(String key, Object facts) {}
    public record Knowledge(String status, List<Evidence> results) {}
}
