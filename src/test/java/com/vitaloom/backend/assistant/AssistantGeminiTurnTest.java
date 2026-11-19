package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AssistantGeminiTurnTest {
    @Test
    void executesSafeBatchAndReturnsTheFinalModelText() {
        var prompts = new ArrayList<Prompt>();
        var originalAssistant = assistant("safe", call("", "water", "{}"), assess(), call("", "sleep", "{}"));
        var model = new FakeChatModel(prompt -> {
            prompts.add(prompt);
            return prompts.size() == 1
                    ? responseMessages(assistantWithProperties("internal", true), originalAssistant,
                            assistant("stale response before tools run"))
                    : responseMessages(assistantWithProperties("internal", true),
                            assistant("", finish("summary", "GROUNDED", "e1")));
        });
        var sleep = callback("sleep", "{\"window\":\"recent\"}", "sleep evidence");
        var water = callback("water", "{\"date\":\"2026-11-18\"}", "water evidence");
        var initial = List.<Message>of(new SystemMessage("system"), new UserMessage("hybrid request"));
        var result = new AssistantGeminiTurn(model, "test-model").run(initial, List.of(sleep, water), () -> true);

        assertThat(result.text()).isEqualTo(finishJson("summary", "GROUNDED", "e1"));
        assertThat(result.grounding()).isEqualTo(AssistantGeminiTurn.Grounding.EVIDENCE);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).getInstructions()).containsExactlyElementsOf(initial);
        var options = (GoogleGenAiChatOptions) prompts.getFirst().getOptions();
        assertThat(options.getToolChoice().mode()).isEqualTo(GoogleGenAiChatOptions.ToolChoice.Mode.ANY);
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .contains("assessTurnSafety", "sleep", "water");
        assertThat(prompts.get(1).getInstructions()).contains(initial.get(0), initial.get(1),
                prompts.get(0).getInstructions().get(0));
        assertThat(prompts.get(1).getInstructions()).contains(originalAssistant);
        assertThat(prompts.get(1).getInstructions()).noneMatch(message -> message instanceof AssistantMessage assistant
                && "stale response before tools run".equals(assistant.getText()));
        var toolResults = prompts.get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        assertThat(toolResults.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("", "", "");
        assertThat(toolResults.getResponses()).extracting(ToolResponseMessage.ToolResponse::name)
                .containsExactly("water", "assessTurnSafety", "sleep");
        assertThat(toolResults.getResponses()).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("water evidence",
                        "{\"classification\":\"SAFE\",\"grounding\":\"EVIDENCE\"}", "sleep evidence");
        assertThat(sleep.calls).containsExactly("{}");
        assertThat(water.calls).containsExactly("{}");
    }

    @Test
    void stopsBeforeEvidenceWhenSafetyAssessmentIsUrgent() {
        var promptCount = new AtomicInteger();
        var model = new FakeChatModel(prompt -> {
            promptCount.incrementAndGet();
            return response(assistant("unsafe", assess("URGENT"), call("sleep-call", "sleep", "{}")));
        });
        var sleep = callback("sleep", "{}", "must not run");

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("request")), List.of(sleep), () -> true))
                .isInstanceOf(AssistantGeminiTurn.UnsafeRequestException.class);

        assertThat(promptCount).hasValue(1);
        assertThat(sleep.calls).isEmpty();
    }

    @Test
    void returnsTheModelSelectedGroundingForTheCallerToValidate() {
        var prompts = new ArrayList<Prompt>();
        var model = new FakeChatModel(prompt -> {
            prompts.add(prompt);
            return response(assistant("", assess("SAFE", "APPROVED_KNOWLEDGE"),
                    finish("grounded answer", "GROUNDED", "e1")));
        });

        var result = new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("health question")), List.of(), () -> true);

        assertThat(result.text()).isEqualTo(finishJson("grounded answer", "GROUNDED", "e1"));
        assertThat(result.grounding()).isEqualTo(AssistantGeminiTurn.Grounding.APPROVED_KNOWLEDGE);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void stopsBeforeEvidenceWhenAuthorizationChangesAfterModelSelection() {
        var current = new AtomicBoolean(true);
        var model = new FakeChatModel(prompt -> {
            current.set(false);
            return response(assistant("safe", assess(), call("sleep-call", "sleep", "{}")));
        });
        var sleep = callback("sleep", "{}", "must not run");

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("request")), List.of(sleep), current::get))
                .isInstanceOf(AssistantGeminiTurn.AccessChangedException.class);

        assertThat(sleep.calls).isEmpty();
    }

    @Test
    void doesNotExecuteSameToolCallTwice() {
        var responseCount = new AtomicInteger();
        var model = new FakeChatModel(prompt -> responseCount.getAndIncrement() == 0
                ? response(assistant("safe", assess(), call("same-id", "sleep", "{}")))
                : response(assistant("safe", call("same-id", "sleep", "{}"))));
        var sleep = callback("sleep", "{}", "evidence");

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("request")), List.of(sleep), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");

        assertThat(sleep.calls).containsExactly("{}");
    }

    @Test
    void rejectsFinishRequestedAlongsideEvidenceBeforeExecutingAnyTool() {
        var sleep = callback("sleep", "{}", "evidence");
        var model = new FakeChatModel(prompt -> response(assistant("", assess(),
                finish("unsupported premature output", "CONVERSATION"), call("sleep-call", "sleep", "{}"))));

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("request")), List.of(sleep), () -> true))
                .isInstanceOf(AssistantGeminiTurn.InvalidFinishException.class);

        assertThat(sleep.calls).isEmpty();
    }

    @Test
    void returnsJournalActionWithoutExecutingIt() {
        var journal = callback("recordWater", "{}", "must not execute");
        var model = new FakeChatModel(prompt -> response(assistant("", assess(),
                call("write", "recordWater", "{\"amount\":\"250ml\"}"))));

        var result = new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("Log 250ml water")), List.of(journal), () -> true);

        assertThat(result.action()).isNotNull();
        assertThat(result.action().name()).isEqualTo("recordWater");
        assertThat(result.action().arguments()).isEqualTo("{\"amount\":\"250ml\"}");
        assertThat(journal.calls).isEmpty();
    }

    @Test
    void rejectsMultipleJournalActionsBeforeReturningAnyAction() {
        var water = callback("recordWater", "{}", "must not execute");
        var sleep = callback("recordSleep", "{}", "must not execute");
        var model = new FakeChatModel(prompt -> response(assistant("", assess(),
                call("water", "recordWater", "{}"), call("sleep", "recordSleep", "{}"))));

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("record both")), List.of(water, sleep), () -> true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(water.calls).isEmpty();
        assertThat(sleep.calls).isEmpty();
    }

    @Test
    void rejectsJournalActionMixedWithEvidenceBeforeExecutingEither() {
        var journal = callback("recordWater", "{}", "must not execute");
        var evidence = callback("readWater", "{}", "must not execute");
        var model = new FakeChatModel(prompt -> response(assistant("", assess(),
                call("write", "recordWater", "{}"), call("read", "readWater", "{}"))));

        assertThatThrownBy(() -> new AssistantGeminiTurn(model, "test-model")
                .run(List.of(new UserMessage("record and read")), List.of(journal, evidence), () -> true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(journal.calls).isEmpty();
        assertThat(evidence.calls).isEmpty();
    }

    private static RecordingToolCallback callback(String name, String schema, String result) {
        return new RecordingToolCallback(name, schema, result);
    }

    private static ToolCall assess() {
        return call("", "assessTurnSafety", "{\"classification\":\"SAFE\",\"grounding\":\"EVIDENCE\"}");
    }

    private static ToolCall assess(String classification) {
        return assess(classification, "EVIDENCE");
    }

    private static ToolCall assess(String classification, String grounding) {
        return call("", "assessTurnSafety", "{\"classification\":\"" + classification
                + "\",\"grounding\":\"" + grounding + "\"}");
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, "function", name, arguments);
    }

    private static ToolCall finish(String text, String kind, String... sources) {
        return call("", "finishConversation", finishJson(text, kind, sources));
    }

    private static String finishJson(String text, String kind, String... sources) {
        return "{\"parts\":[{\"kind\":\"" + kind + "\",\"text\":\"" + text
                + "\",\"sources\":[" + java.util.Arrays.stream(sources).map(source -> "\"" + source + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "]}]}";
    }

    private static AssistantMessage assistant(String text, ToolCall... calls) {
        return AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
    }

    private static AssistantMessage assistantWithProperties(String text, boolean thought) {
        return AssistantMessage.builder().content(text).properties(java.util.Map.of("isThought", thought)).build();
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse responseMessages(AssistantMessage... messages) {
        return new ChatResponse(java.util.Arrays.stream(messages).map(Generation::new).toList());
    }

    private static final class FakeChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> responses;

        private FakeChatModel(Function<Prompt, ChatResponse> responses) { this.responses = responses; }

        @Override public ChatResponse call(Prompt prompt) { return responses.apply(prompt); }
    }

    private static final class RecordingToolCallback implements ToolCallback {
        private final ToolDefinition definition;
        private final String result;
        private final List<String> calls = new ArrayList<>();

        private RecordingToolCallback(String name, String schema, String result) {
            definition = ToolDefinition.builder().name(name).description(name).inputSchema(schema).build();
            this.result = result;
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String arguments) {
            calls.add(arguments);
            return result;
        }
    }
}
