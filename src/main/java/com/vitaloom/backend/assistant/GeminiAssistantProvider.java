package com.vitaloom.backend.assistant;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class GeminiAssistantProvider implements AssistantProvider, AutoCloseable {
    private static final ObjectReader FAQ_REPLY_READER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()).reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Client client;
    private final GoogleGenAiChatModel model;
    private final String chatModel;

    GeminiAssistantProvider(String key, String chatModel) {
        this(Client.builder().vertexAI(false).apiKey(key)
                .httpOptions(HttpOptions.builder()
                        .baseUrl("https://generativelanguage.googleapis.com")
                        .timeout(10_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1))
                        .build())
                .build(), chatModel);
    }

    GeminiAssistantProvider(Client client, String chatModel) {
        this.client = client;
        this.chatModel = chatModel;
        model = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder()
                        .model(chatModel)
                        .maxOutputTokens(128)
                        .googleSearchRetrieval(false)
                        .build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
    }

    @Override
    public boolean confirmsFixture() {
        return confirmsFixture(AssistantFixture.QUESTION, AssistantFixture.ANSWER);
    }

    @Override
    public boolean confirmsFixture(String question, String evidence) {
        var response = call(() -> model.call(new Prompt(AssistantFixture.prompt(question, evidence))));
        var result = response.getResult();
        return result != null && result.getOutput() != null
                && AssistantFixture.matchesAnswer(evidence, result.getOutput().getText());
    }

    @Override
    public ToolCall selectVoiceTool(String transcript) {
        return call(() -> AssistantVoiceTools.select(model, chatModel, transcript));
    }

    @Override
    public AssistantGeminiTurn.Result converse(List<Message> messages, List<ToolCallback> tools,
            BooleanSupplier current) {
        return call(() -> new AssistantGeminiTurn(model, chatModel).run(messages, tools, current));
    }

    @Override
    public List<Float> embed(String modelName, String title, String text, boolean document, int dimensions) {
        var config = EmbedContentConfig.builder().outputDimensionality(dimensions);
        String input;
        if ("gemini-embedding-001".equals(modelName)) {
            config.taskType(document ? "RETRIEVAL_DOCUMENT" : "QUESTION_ANSWERING");
            if (document) config.title(title);
            input = text;
        } else {
            input = (document ? "title: " + title + " | text: " : "task: question answering | query: ") + text;
        }
        var embeddings = call(() -> client.models.embedContent(modelName, input, config.build()))
                .embeddings().orElseThrow();
        if (embeddings.size() != 1) throw new IllegalStateException("Unexpected embedding count");
        return embeddings.getFirst().values().orElseThrow();
    }

    @Override
    public boolean confirmsSyntheticSleep(String day, BooleanSupplier current, BooleanSupplier verifyRecord) {
        var input = new UserMessage(AssistantFixture.SLEEP_QUESTION + "\nNgày hôm nay là " + day
                + ". Dùng tool. Sau khi có kết quả, chỉ in durationMinutes dưới dạng số nguyên.");
        var tool = new SyntheticSleepTool();
        var callbacks = ToolCallbacks.from(tool);
        if (!current.getAsBoolean()) return false;
        var firstOptions = GoogleGenAiChatOptions.builder().toolCallbacks(callbacks)
                .toolChoice(new ToolChoice(ToolChoice.Mode.ANY, List.of(SyntheticSleepTool.NAME))).build();
        var first = call(() -> model.call(new Prompt(List.of(input), firstOptions)));
        if (first.getResults().size() != 1 || first.getResult().getOutput() == null) return false;
        var modelTurn = first.getResult().getOutput();
        var calls = modelTurn.getToolCalls();
        if (calls.size() != 1) return false;
        var selected = calls.getFirst();
        if (!SyntheticSleepTool.NAME.equals(selected.name()) || !matchesToolDates(selected.arguments(), day)
                || !current.getAsBoolean() || !verifyRecord.getAsBoolean() || !current.getAsBoolean()) return false;

        var response = new ToolResponseMessage.ToolResponse(selected.id(), selected.name(),
                new ObjectMapper().writeValueAsString(tool.getSleepRecords(day, day)));
        var result = ToolResponseMessage.builder().responses(List.of(response)).build();
        var secondOptions = GoogleGenAiChatOptions.builder().toolCallbacks(callbacks)
                .toolChoice(new ToolChoice(ToolChoice.Mode.NONE, List.of())).build();
        // Keep the original assistant message because Gemini 3 requires its thought signature on the next turn.
        var second = call(() -> model.call(new Prompt(List.of(input, modelTurn, result), secondOptions)));
        var output = second.getResult();
        return output != null && output.getOutput() != null && output.getOutput().getToolCalls().isEmpty()
                && AssistantFixture.matchesAnswer(Integer.toString(AssistantFixture.SLEEP_MINUTES),
                        output.getOutput().getText())
                && current.getAsBoolean() && verifyRecord.getAsBoolean() && current.getAsBoolean();
    }

    private static boolean matchesToolDates(String arguments, String day) {
        if (arguments == null || arguments.isBlank()) return false;
        try {
            JsonNode dates = FAQ_REPLY_READER.readTree(arguments);
            return dates.isObject() && dates.size() == 2
                    && day.equals(dates.path("from").stringValue())
                    && day.equals(dates.path("to").stringValue());
        } catch (JacksonException failure) {
            return false;
        }
    }

    private static final class SyntheticSleepTool {
        static final String NAME = "getSleepRecords";

        @Tool(name = NAME, description = "Reads a synthetic test Sleep Journal MAIN record for the requested inclusive date range.")
        public Map<String, Object> getSleepRecords(
                @ToolParam(description = "Start date (YYYY-MM-DD) supplied in the question.") String from,
                @ToolParam(description = "End date (YYYY-MM-DD) supplied in the question.") String to) {
            return Map.of("date", from, "sleepType", "MAIN", "method", "DURATION",
                    "durationMinutes", AssistantFixture.SLEEP_MINUTES);
        }
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api && api.code() == 429) {
                    LoggerFactory.getLogger(GeminiAssistantProvider.class)
                            .warn("Assistant Gemini quota exhausted (HTTP 429). Request stopped.");
                    break;
                }
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
