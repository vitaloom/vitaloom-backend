package com.vitaloom.backend.assistant;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

interface AssistantProvider {
    boolean confirmsFixture();

    boolean confirmsFixture(String question, String evidence);

    String answerFaq(String question, String faqQuestion, String faqAnswer);

    List<FaqPart> answerFaqContext(String question, List<FaqEvidence> evidence);

    boolean confirmsSyntheticSleep(String day, BooleanSupplier current, BooleanSupplier verifyRecord);

    List<Float> embed(String model, String title, String text, boolean document, int dimensions);

    ToolCall selectVoiceTool(String transcript);

    void selectPersonalTool(String question, AssistantPersonalDataTools tools, BooleanSupplier current);

    record FaqEvidence(String id, String question, String answer) {}

    record FaqPart(String faqId, String answer) {}

    final class InvalidFaqResponse extends RuntimeException {
        InvalidFaqResponse() {
            super("Provider returned no usable FAQ output");
        }
    }
}
