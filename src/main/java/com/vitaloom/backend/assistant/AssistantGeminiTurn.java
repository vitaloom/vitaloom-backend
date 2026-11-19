package com.vitaloom.backend.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice.Mode;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Runs one Gemini conversational turn, including its bounded function-call round trips. */
final class AssistantGeminiTurn {
    private static final String SAFETY_TOOL = "assessTurnSafety";
    private static final String FINISH_TOOL = "finishConversation";
    private static final int MAX_DECLARED_TOOLS = 8;
    private static final int MAX_EVIDENCE_CALLS = 6;
    private static final int MAX_EVIDENCE_ROUNDS = 3;
    private static final tools.jackson.databind.ObjectReader JSON_READER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ToolCallback SAFETY_CALLBACK = new SafetyCallback();
    private static final ToolCallback FINISH_CALLBACK = new FinishCallback();

    private final ChatModel model;
    private final String modelName;

    AssistantGeminiTurn(ChatModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    Result run(List<Message> messages, List<ToolCallback> tools, BooleanSupplier current) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("Messages are required");
        if (tools == null || tools.size() > MAX_DECLARED_TOOLS) {
            throw new IllegalArgumentException("At most eight evidence tools may be registered");
        }
        Map<String, ToolCallback> callbacks = indexCallbacks(tools);
        List<ToolCallback> declared = new ArrayList<>(tools);
        declared.add(SAFETY_CALLBACK);
        declared.add(FINISH_CALLBACK);
        List<Message> turnMessages = new ArrayList<>(messages);
        Set<String> executedCallIds = new HashSet<>();
        Set<ExecutedCall> executedCalls = new HashSet<>();
        boolean safetyAssessed = false;
        Grounding grounding = null;
        int evidenceRounds = 0;

        while (true) {
            ensureCurrent(current);
            var optionsBuilder = GoogleGenAiChatOptions.builder().model(modelName).maxOutputTokens(768)
                    .googleSearchRetrieval(false).toolCallbacks(declared)
                    .toolChoice(ToolChoice.builder().mode(safetyAssessed ? Mode.AUTO : Mode.ANY).build());
            if ("gemini-3.1-flash-lite".equals(modelName)) {
                optionsBuilder.thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL);
            }
            var options = optionsBuilder.build();
            ChatResponse response = model.call(new Prompt(List.copyOf(turnMessages), options));
            ensureCurrent(current);
            List<AssistantMessage> assistantMessages = assistantMessages(response);
            List<AssistantMessage.ToolCall> requestedCalls = assistantMessages.stream()
                    .flatMap(message -> message.getToolCalls().stream()).toList();
            if (requestedCalls.isEmpty()) {
                throw new InvalidFinishException("Gemini omitted finishConversation");
            }

            var safetyCalls = requestedCalls.stream().filter(call -> SAFETY_TOOL.equals(call.name())).toList();
            if (!safetyAssessed) {
                if (safetyCalls.size() != 1) {
                    throw new IllegalStateException("Gemini must assess safety exactly once before using evidence tools");
                }
                SafetyAssessment assessment = parseSafety(safetyCalls.getFirst().arguments());
                safetyAssessed = true;
                grounding = assessment.grounding();
                if (assessment.classification() != SafetyClassification.SAFE) {
                    throw new UnsafeRequestException(assessment.classification());
                }
            } else if (!safetyCalls.isEmpty()) {
                throw new IllegalStateException("Gemini cannot reassess safety during a tool round trip");
            }

            List<AssistantMessage.ToolCall> evidenceCalls = requestedCalls.stream()
                    .filter(call -> !SAFETY_TOOL.equals(call.name()) && !FINISH_TOOL.equals(call.name())).toList();
            var finishCalls = requestedCalls.stream().filter(call -> FINISH_TOOL.equals(call.name())).toList();
            if (!finishCalls.isEmpty()) {
                if (finishCalls.size() != 1 || !evidenceCalls.isEmpty()) {
                    throw new InvalidFinishException("Gemini must finish once and without evidence calls");
                }
                if (!safetyAssessed) throw new IllegalStateException("Gemini omitted the required safety assessment");
                ensureCurrent(current);
                return new Result(validateFinish(finishCalls.getFirst().arguments()), grounding);
            }
            Map<AssistantMessage.ToolCall, ToolResponseMessage.ToolResponse> resultsByCall = new IdentityHashMap<>();
            if (!safetyCalls.isEmpty()) {
                var safety = safetyCalls.getFirst();
                resultsByCall.put(safety, new ToolResponseMessage.ToolResponse(safety.id(), safety.name(),
                        "{\"classification\":\"SAFE\",\"grounding\":\"" + grounding + "\"}"));
            }
            if (evidenceCalls.isEmpty()) {
                turnMessages.addAll(assistantMessages);
                turnMessages.add(ToolResponseMessage.builder().responses(requestedCalls.stream()
                        .map(resultsByCall::get).toList()).build());
                continue;
            }
            if (evidenceRounds >= MAX_EVIDENCE_ROUNDS) {
                throw new IllegalStateException("Gemini exceeded the evidence tool round limit");
            }
            if (evidenceCalls.size() + executedCalls.size() > MAX_EVIDENCE_CALLS) {
                throw new IllegalStateException("Gemini exceeded the evidence tool call budget");
            }
            validateCalls(evidenceCalls, callbacks, executedCallIds, executedCalls);

            for (var call : evidenceCalls) {
                ensureCurrent(current);
                String result = callbacks.get(call.name()).call(call.arguments());
                ensureCurrent(current);
                resultsByCall.put(call, new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
                if (call.id() != null && !call.id().isBlank()) executedCallIds.add(call.id());
                executedCalls.add(executedCall(call));
            }

            turnMessages.addAll(assistantMessages);
            turnMessages.add(ToolResponseMessage.builder().responses(requestedCalls.stream()
                    .map(resultsByCall::get).toList()).build());
            evidenceRounds++;
        }
    }

    private static Map<String, ToolCallback> indexCallbacks(List<ToolCallback> tools) {
        Map<String, ToolCallback> callbacks = new HashMap<>();
        for (ToolCallback callback : tools) {
            if (callback == null) throw new IllegalArgumentException("Evidence tool cannot be null");
            String name = callback.getToolDefinition().name();
            if (name == null || name.isBlank() || SAFETY_TOOL.equals(name) || FINISH_TOOL.equals(name)
                    || callbacks.putIfAbsent(name, callback) != null) {
                throw new IllegalArgumentException("Evidence tool names must be unique and cannot override safety assessment");
            }
            ToolMetadata metadata = callback.getToolMetadata();
            if (metadata != null && metadata.returnDirect()) {
                throw new IllegalArgumentException("Direct-return tools cannot be used in a grounded conversation");
            }
        }
        return Map.copyOf(callbacks);
    }

    private static void validateCalls(List<AssistantMessage.ToolCall> calls, Map<String, ToolCallback> callbacks,
            Set<String> executedCallIds, Set<ExecutedCall> executedCalls) {
        Set<String> idsInBatch = new HashSet<>();
        Set<ExecutedCall> callsInBatch = new HashSet<>();
        for (var call : calls) {
            ExecutedCall key = executedCall(call);
            if (!callbacks.containsKey(call.name())) throw new IllegalStateException("Gemini requested an unknown tool");
            if ((call.id() != null && !call.id().isBlank() && !idsInBatch.add(call.id()))
                    || !callsInBatch.add(key) || executedCallIds.contains(call.id()) || executedCalls.contains(key)) {
                throw new IllegalStateException("Gemini returned a duplicate tool call");
            }
        }
    }

    private static ExecutedCall executedCall(AssistantMessage.ToolCall call) {
        if (call.arguments() == null || call.arguments().isBlank()) {
            throw new IllegalStateException("Gemini returned empty tool arguments");
        }
        try {
            return new ExecutedCall(call.name(), JSON_READER.readTree(call.arguments()));
        } catch (JacksonException failure) {
            throw new IllegalStateException("Gemini returned invalid tool arguments", failure);
        }
    }

    private record ExecutedCall(String name, JsonNode arguments) {}

    private static List<AssistantMessage> assistantMessages(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("Gemini returned no assistant messages");
        }
        List<AssistantMessage> messages = response.getResults().stream()
                .map(generation -> generation == null ? null : generation.getOutput()).toList();
        if (messages.stream().anyMatch(message -> message == null)) {
            throw new IllegalStateException("Gemini returned an invalid assistant message");
        }
        int lastToolCall = -1;
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).hasToolCalls()) lastToolCall = index;
        }
        return lastToolCall < 0 ? messages : List.copyOf(messages.subList(0, lastToolCall + 1));
    }

    private static String validateFinish(String arguments) {
        if (arguments == null || arguments.isBlank() || arguments.length() > 6000) {
            throw new InvalidFinishException("Gemini returned invalid final answer data");
        }
        try {
            JsonNode root = JSON_READER.readTree(arguments);
            JsonNode parts = root.path("parts");
            if (!root.isObject() || root.size() != 1 || !parts.isArray() || parts.size() < 1 || parts.size() > 4) {
                throw new InvalidFinishException("Gemini returned an invalid final answer shape");
            }
            for (JsonNode part : parts) {
                JsonNode kind = part.path("kind");
                JsonNode text = part.path("text");
                JsonNode sources = part.path("sources");
                if (!part.isObject() || part.size() != 3 || !kind.isString() || !text.isString()
                        || text.stringValue().isBlank() || text.stringValue().length() > 1800
                        || !sources.isArray() || sources.size() > 4) {
                    throw new InvalidFinishException("Gemini returned an invalid final answer part");
                }
                String kindValue = kind.stringValue();
                if (!Set.of("GROUNDED", "CONVERSATION", "CLARIFICATION", "LIMITATION").contains(kindValue)) {
                    throw new InvalidFinishException("Gemini returned an invalid final answer kind");
                }
                boolean grounded = "GROUNDED".equals(kindValue);
                if (grounded == sources.isEmpty()) {
                    throw new InvalidFinishException("Final answer evidence does not match its kind");
                }
                Set<String> distinctSources = new HashSet<>();
                for (JsonNode source : sources) {
                    if (!source.isString() || !source.stringValue().matches("e[1-9][0-9]*")
                            || !distinctSources.add(source.stringValue())) {
                        throw new InvalidFinishException("Final answer contains an invalid evidence key");
                    }
                }
            }
            return arguments;
        } catch (JacksonException failure) {
            throw new InvalidFinishException("Gemini returned malformed final answer data", failure);
        }
    }

    private static SafetyAssessment parseSafety(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalStateException("Gemini returned an invalid safety assessment");
        }
        try {
            JsonNode root = JSON_READER.readTree(arguments);
            JsonNode classification = root.path("classification");
            JsonNode grounding = root.path("grounding");
            if (!root.isObject() || root.size() != 2 || !classification.isString() || !grounding.isString()) {
                throw new IllegalStateException("Gemini returned an invalid safety assessment");
            }
            return new SafetyAssessment(SafetyClassification.valueOf(classification.stringValue()),
                    Grounding.valueOf(grounding.stringValue()));
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new IllegalStateException("Gemini returned an invalid safety assessment", failure);
        }
    }

    private static void ensureCurrent(BooleanSupplier current) {
        if (current == null || !current.getAsBoolean()) throw new AccessChangedException();
    }

    enum SafetyClassification { SAFE, URGENT, MEDICAL_RESTRICTED }
    enum Grounding { OPEN, EVIDENCE, APPROVED_KNOWLEDGE }

    record Result(String text, Grounding grounding) {}
    private record SafetyAssessment(SafetyClassification classification, Grounding grounding) {}

    static final class UnsafeRequestException extends RuntimeException {
        private final SafetyClassification classification;

        UnsafeRequestException(SafetyClassification classification) {
            super("The request requires a safety response");
            this.classification = classification;
        }

        SafetyClassification classification() { return classification; }
    }

    static final class AccessChangedException extends RuntimeException {
        AccessChangedException() { super("Assistant access changed during the turn"); }
    }

    static final class InvalidFinishException extends RuntimeException {
        InvalidFinishException(String message) { super(message); }
        InvalidFinishException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class SafetyCallback implements ToolCallback {
        private static final ToolDefinition DEFINITION = ToolDefinition.builder().name(SAFETY_TOOL)
                .description("Assess safety and the factual grounding needed for the current conversational response before requesting evidence. Use OPEN only for non-factual conversation or clarification, EVIDENCE for factual claims about app capabilities or recorded metrics, and APPROVED_KNOWLEDGE for health guidance claims supported by approved health education. Classify immediate danger as URGENT and requests for diagnosis or medication instructions as MEDICAL_RESTRICTED.")
                .inputSchema("""
                        {"type":"object","properties":{"classification":{"type":"string","enum":["SAFE","URGENT","MEDICAL_RESTRICTED"]},"grounding":{"type":"string","enum":["OPEN","EVIDENCE","APPROVED_KNOWLEDGE"]}},"required":["classification","grounding"],"additionalProperties":false}
                        """).build();

        @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(false).build(); }
        @Override public String call(String arguments) {
            var assessment = parseSafety(arguments);
            return "{\"classification\":\"" + assessment.classification()
                    + "\",\"grounding\":\"" + assessment.grounding() + "\"}";
        }
    }

    private static final class FinishCallback implements ToolCallback {
        private static final ToolDefinition DEFINITION = ToolDefinition.builder().name(FINISH_TOOL)
                .description("Finish the conversation with the grounded, user-facing response. Every answer part must identify its kind, concise text, and evidence keys used. Use GROUNDED for claims supported by provided evidence, CONVERSATION only for non-factual conversational wording, CLARIFICATION to ask the user for information, and LIMITATION to explain missing or insufficient evidence. Cite only evidence keys supplied by tools. Do not include source keys for conversation, clarification, or limitation parts. This function records no action and ends the current turn.")
                .inputSchema("""
                        {"type":"object","properties":{"parts":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"object","properties":{"kind":{"type":"string","enum":["GROUNDED","CONVERSATION","CLARIFICATION","LIMITATION"]},"text":{"type":"string","minLength":1,"maxLength":1800},"sources":{"type":"array","maxItems":4,"uniqueItems":true,"items":{"type":"string","pattern":"^e[1-9][0-9]*$"}}},"required":["kind","text","sources"],"additionalProperties":false}}},"required":["parts"],"additionalProperties":false}
                        """).build();

        @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(false).build(); }
        @Override public String call(String arguments) {
            throw new IllegalStateException("finishConversation is a reserved no-effect declaration");
        }
    }
}
