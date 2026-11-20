package com.vitaloom.backend.assistant;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

final class AssistantNativeChat {
    private static final Set<String> FINISH_REASONS = Set.of("STOP", "FINISH_REASON_UNSPECIFIED");
    private final ChatClient client;
    private final String modelName;

    AssistantNativeChat(ChatModel model, String modelName) {
        var manager = DefaultToolCallingManager.builder().resolutionFallbackEnabled(false)
                .maxTotalToolCalls(6).toolExecutionExceptionProcessor(failure -> {
                    if (failure.getCause() instanceof RuntimeException cause) throw cause;
                    throw failure;
                }).build();
        client = ChatClient.builder(model)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build()).build();
        this.modelName = modelName;
    }

    String call(List<Message> messages, List<ToolCallback> tools, BooleanSupplier current,
            Consumer<List<ToolCall>> beforeTools) {
        var names = tools.stream().map(tool -> tool.getToolDefinition().name()).collect(Collectors.toSet());
        var response = client.prompt().messages(messages).tools(tools)
                .options(GoogleGenAiChatOptions.builder().model(modelName).candidateCount(1)
                        .maxOutputTokens(768).googleSearchRetrieval(false)
                        .toolChoice(new ToolChoice(ToolChoice.Mode.AUTO, List.of())))
                .advisors(new RoundGuard(current, names, beforeTools)).call().chatResponse();
        return visibleText(response);
    }

    static void requireCurrent(BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new AccessChanged();
    }

    static String visibleText(ChatResponse response) {
        verifyResponse(response);
        if (response.hasToolCalls()) throw new InvalidResponse();
        var prose = response.getResults().stream().map(generation -> generation.getOutput())
                .filter(message -> !Boolean.TRUE.equals(
                        message.getMetadata().get(GoogleGenAiChatModel.THOUGHT_METADATA_KEY)))
                .map(message -> message.getText()).filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n")).strip();
        if (prose.isEmpty() || prose.length() > 6000) throw new InvalidResponse();
        return prose;
    }

    private static void verifyResponse(ChatResponse response) {
        if (response == null || response.getResults().isEmpty() || response.getResults().stream().anyMatch(result ->
                result.getMetadata().getFinishReason() != null && !result.getMetadata().getFinishReason().isBlank()
                        && !FINISH_REASONS.contains(result.getMetadata().getFinishReason()))) {
            throw new InvalidResponse();
        }
    }

    private record RoundGuard(BooleanSupplier current, Set<String> names, Consumer<List<ToolCall>> beforeTools)
            implements CallAdvisor {
        @Override public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 1; }

        @Override public String getName() { return "VitaLoom tool dispatch guard"; }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            var result = GeminiAssistantProvider.requestWithTransientRetry(current,
                    () -> chain.copy(this).nextCall(request));
            requireCurrent(current);
            var response = result.chatResponse();
            verifyResponse(response);
            var batches = response.getResults().stream().map(generation -> generation.getOutput().getToolCalls())
                    .filter(calls -> !calls.isEmpty()).toList();
            if (!batches.isEmpty()) {
                // The native manager executes only the first tool-bearing generation.
                if (batches.size() != 1 || batches.getFirst().stream().anyMatch(call -> !names.contains(call.name()))) {
                    throw new InvalidResponse();
                }
                beforeTools.accept(batches.getFirst());
                requireCurrent(current);
            }
            return result;
        }
    }

    static final class AccessChanged extends RuntimeException {}
    static final class InvalidResponse extends RuntimeException {}
}
