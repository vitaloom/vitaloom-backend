package com.vitaloom.backend.assistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

final class AssistantConversationReply {
    private static final int MAX_PARTS = 4;
    private static final int MAX_TEXT = 1800;
    private static final int MAX_REPLY = 6000;
    private static final int MAX_CITATIONS = 4;
    private static final Pattern INLINE_EVIDENCE_KEY = Pattern.compile("\\s*\\[e[0-9]+(?:\\s*,\\s*e[0-9]+)*]");
    private static final tools.jackson.databind.ObjectReader READER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AssistantConversationReply() {}

    static AssistantCourseAnswers.Answer parse(String json, Map<String, EvidenceSource> evidence) {
        return parse(json, evidence, true);
    }

    static AssistantCourseAnswers.Answer parse(String json, Map<String, EvidenceSource> evidence,
            boolean allowOpenConversation) {
        if (json == null || json.isBlank() || json.length() > MAX_REPLY) throw new InvalidReply();
        try {
            var root = READER.readTree(json);
            var parts = root.path("parts");
            if (!root.isObject() || root.size() != 1 || !parts.isArray() || parts.isEmpty()
                    || parts.size() > MAX_PARTS) throw new InvalidReply();
            var result = new ArrayList<AssistantCourseAnswers.Part>();
            var verified = new HashSet<String>();
            boolean writeClarification = false;
            for (var part : parts) {
                var kind = part.path("kind");
                var text = part.path("text");
                var citations = part.path("sources");
                if (!part.isObject() || part.size() != 3 || !kind.isString() || !text.isString()
                        || text.stringValue().isBlank() || text.stringValue().length() > MAX_TEXT
                        || !citations.isArray() || citations.size() > MAX_CITATIONS) throw new InvalidReply();
                boolean grounded = "GROUNDED".equals(kind.stringValue());
                if (grounded == citations.isEmpty()) throw new InvalidReply();
                if ("WRITE_CLARIFICATION".equals(kind.stringValue())) {
                    if (parts.size() != 1 || !citations.isEmpty()) throw new InvalidReply();
                    writeClarification = true;
                }
                if (!allowOpenConversation && "CONVERSATION".equals(kind.stringValue())) continue;
                String status = switch (kind.stringValue()) {
                    case "GROUNDED", "CONVERSATION", "CLARIFICATION", "WRITE_CLARIFICATION" -> "ANSWERED";
                    case "LIMITATION" -> "INSUFFICIENT_EVIDENCE";
                    default -> throw new InvalidReply();
                };
                String prose = INLINE_EVIDENCE_KEY.matcher(text.stringValue()).replaceAll("").strip();
                if (prose.isBlank()) throw new InvalidReply();
                if (citations.isEmpty()) {
                    result.add(new AssistantCourseAnswers.Part(status, prose, null));
                    continue;
                }
                var ids = new HashSet<String>();
                for (var citation : citations) {
                    if (!citation.isString() || !ids.add(citation.stringValue())) throw new InvalidReply();
                    String id = citation.stringValue();
                    var source = evidence.get(id);
                    if (source == null || (verified.add(id) && !source.current().getAsBoolean())) {
                        throw new InvalidReply();
                    }
                    result.add(new AssistantCourseAnswers.Part(status, prose, source.source()));
                    prose = "";
                }
            }
            if (result.isEmpty()) throw new InvalidReply();
            String outcome = writeClarification ? "WRITE_CLARIFICATION"
                    : result.stream().map(AssistantCourseAnswers.Part::status).distinct().count() > 1
                            ? "PARTIAL" : result.getFirst().status();
            return new AssistantCourseAnswers.Answer(outcome, List.copyOf(result), null);
        } catch (JacksonException invalid) {
            throw new InvalidReply();
        }
    }

    record EvidenceSource(AssistantCourseAnswers.Source source, BooleanSupplier current) {}

    static final class InvalidReply extends RuntimeException {}
}
