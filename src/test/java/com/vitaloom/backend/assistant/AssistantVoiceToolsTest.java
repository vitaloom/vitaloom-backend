package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class AssistantVoiceToolsTest {
    @Test
    void sendsOneTranscriptAndDeclaresOnlyTheTwoVoiceTools() {
        var model = mock(ChatModel.class);
        String transcript = "Nhập 250 ml nước";
        var call = new ToolCall("id", "function", "recordWater", "{\"amount\":\"250 ml\"}");
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getUserMessage().getText()).isEqualTo(transcript);
            var options = (ToolCallingChatOptions) prompt.getOptions();
            assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                    .containsExactlyInAnyOrder("recordWater", "recordSleep");
            return response(List.of(call));
        });
        assertThat(AssistantVoiceTools.select(model, "fixture-model", transcript)).isEqualTo(call);
    }

    @Test
    void rejectsResponsesWithMoreThanOneToolCall() {
        var model = mock(ChatModel.class);
        var call = new ToolCall("id", "function", "recordWater", "{\"amount\":\"250 ml\"}");
        when(model.call(any(Prompt.class))).thenReturn(response(List.of(call, call)));
        assertThat(AssistantVoiceTools.select(model, "fixture-model", "Ghi 250 ml nước")).isNull();
    }

    private static ChatResponse response(List<ToolCall> calls) {
        var message = AssistantMessage.builder().content("").toolCalls(calls).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
