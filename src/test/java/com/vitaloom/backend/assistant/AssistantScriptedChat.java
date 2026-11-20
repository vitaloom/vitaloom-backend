package com.vitaloom.backend.assistant;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

final class AssistantScriptedChat {
    static final String ALLOW = "{\"verdict\":\"ALLOW\",\"sources\":[]}";

    private AssistantScriptedChat() {}

    static Answer<String> tool(String name, String args) {
        return invocation -> run(invocation, List.of(call(name, args)), prompt -> prose("Đã xử lý."));
    }

    static ToolCall call(String name, String args) {
        return new ToolCall(name, "function", name, args);
    }

    static String run(InvocationOnMock invocation, List<ToolCall> calls, Function<Prompt, ChatResponse> afterTools) {
        ChatModel scripted = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance)
                        ? afterTools.apply(prompt) : response(calls, "");
            }

            @Override public ChatOptions getOptions() { return GoogleGenAiChatOptions.builder().build(); }
        };
        return new AssistantNativeChat(scripted, "synthetic-model").call(invocation.getArgument(0),
                invocation.getArgument(1), invocation.<BooleanSupplier>getArgument(2),
                invocation.<Consumer<List<ToolCall>>>getArgument(3));
    }

    static ChatResponse prose(String text) {
        return response(List.of(), text);
    }

    static ChatResponse response(List<ToolCall> tools, String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).toolCalls(tools).build())));
    }
}
