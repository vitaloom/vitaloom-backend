package com.vitaloom.backend.assistant;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

interface AssistantProvider {
    boolean confirmsFixture();

    boolean confirmsFixture(String question, String evidence);

    boolean confirmsSyntheticSleep(String day, BooleanSupplier current, BooleanSupplier verifyRecord);

    List<Float> embed(String model, String title, String text, boolean document, int dimensions);

    ToolCall selectVoiceTool(String transcript);

    AssistantGeminiTurn.Result converse(List<Message> messages, List<ToolCallback> tools, BooleanSupplier current);

    record FaqEvidence(String id, String question, String answer) {}

}
