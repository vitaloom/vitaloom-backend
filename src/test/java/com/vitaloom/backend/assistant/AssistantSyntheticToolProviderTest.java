package com.vitaloom.backend.assistant;

import com.google.genai.Client;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSyntheticToolProviderTest {
    private static final String DAY = "2026-11-18";
    private static final String CALL = """
            {"functionCall":{"name":"getSleepRecords","args":{"from":"2026-11-18","to":"2026-11-18"}},
             "thoughtSignature":"dGVzdA=="}
            """;

    private final List<Document> requests = new ArrayList<>();
    private final AtomicInteger checks = new AtomicInteger();
    private volatile String firstParts;
    private volatile String finalParts = "{\"text\":\"423\"}";
    private volatile String finishReason = "STOP";
    private volatile List<Integer> unavailableRequests = List.of();
    private HttpServer server;
    private GeminiAssistantProvider provider;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response;
            try (exchange) {
                var request = Document.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int index;
                synchronized (requests) {
                    requests.add(request);
                    index = requests.size();
                }
                boolean unavailable = unavailableRequests.contains(index);
                String parts = index == 1 ? firstParts : finalParts;
                response = (unavailable
                        ? "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"Synthetic overload\"}}"
                        : "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + parts
                        + "]},\"finishReason\":\"" + finishReason + "\"}],\"modelVersion\":\"gemini-3.1-flash-lite\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(unavailable ? 503 : 200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var client = Client.builder().vertexAI(false).apiKey("synthetic-test-key")
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1)).build())
                .build();
        provider = new GeminiAssistantProvider(client, "gemini-3.1-flash-lite");
    }

    @AfterEach
    void stop() {
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
    }

    @Test
    void nativeAdvisorReturnsVisibleProseAndPreservesTheGoogleFunctionResult() {
        firstParts = """
                {"text":"Thought only","thought":true},
                {"text":"Stale prose"},
                {"functionCall":{"name":"readExample","args":{"amountMl":250}},"thoughtSignature":"dGVzdA=="}
                """;
        finalParts = "{\"text\":\"Hidden\",\"thought\":true},{\"text\":\"Đã đọc dữ liệu.\"}";
        var tool = new ExampleRead();

        String prose = provider.converse(List.of(new UserMessage("Đọc ví dụ")), List.of(ToolCallbacks.from(tool)),
                () -> true, calls -> {
                    assertThat(calls).hasSize(1);
                    assertThat(org.bson.Document.parse(calls.getFirst().arguments()).get("amountMl")).isEqualTo(250);
                });

        assertThat(prose).isEqualTo("Đã đọc dữ liệu.");
        assertThat(tool.calls).isEqualTo(1);
        assertThat(requests).hasSize(2);
        assertThat(choice(requests.getFirst()).getString("mode")).isEqualTo("AUTO");
        var contents = requests.getLast().getList("contents", Document.class);
        assertThat(contents.get(1).getList("parts", Document.class).getFirst().getString("thoughtSignature"))
                .isEqualTo("dGVzdA==");
        var response = contents.get(2).getList("parts", Document.class).getFirst().get("functionResponse", Document.class);
        assertThat(response.get("response", Document.class)).containsEntry("amountMl", 250);
    }

    @Test
    void nativeAdvisorAcceptsAGreetingWithoutTools() {
        firstParts = "{\"text\":\"Chào bạn!\"}";
        var tool = new ExampleRead();

        assertThat(provider.converse(List.of(new UserMessage("Chào")), List.of(ToolCallbacks.from(tool)),
                () -> true, calls -> org.junit.jupiter.api.Assertions.fail("Unexpected tool batch")))
                .isEqualTo("Chào bạn!");
        assertThat(tool.calls).isZero();
        assertThat(requests).hasSize(1);
    }

    @Test
    void retriesTheOverloadedModelRoundWithoutRepeatingTheRead() {
        firstParts = """
                {"functionCall":{"name":"readExample","args":{"amountMl":250}},"thoughtSignature":"dGVzdA=="}
                """;
        finalParts = "{\"text\":\"Nhật ký có 250 ml.\"}";
        unavailableRequests = List.of(2);
        var tool = new ExampleRead();

        assertThat(provider.converse(List.of(new UserMessage("Hôm nay tôi đã uống bao nhiêu nước?")),
                List.of(ToolCallbacks.from(tool)), () -> true, calls -> {})).isEqualTo("Nhật ký có 250 ml.");

        assertThat(tool.calls).isEqualTo(1);
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2)).isEqualTo(requests.get(1));
    }

    @Test
    void retriesAnOverloadedPolicyRequestOnce() {
        finalParts = new Document("text", AssistantScriptedChat.ALLOW).toJson();
        unavailableRequests = List.of(1);

        assertThat(provider.checkPolicy(List.of(new UserMessage("Check synthetic read")), () -> true))
                .isEqualTo(AssistantScriptedChat.ALLOW);

        assertThat(requests).hasSize(2);
        assertThat(requests.getLast()).isEqualTo(requests.getFirst());
        assertThat(requests.getLast()).doesNotContainKey("tools");
    }

    @Test
    void stopsAfterTwoOverloadResponses() {
        unavailableRequests = List.of(1, 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                provider.checkPolicy(List.of(new UserMessage("Check synthetic read")), () -> true))
                .hasRootCauseInstanceOf(com.google.genai.errors.ServerException.class);
        assertThat(requests).hasSize(2);
    }

    @Test
    void rechecksAccessBeforeRetryingTheProvider() {
        unavailableRequests = List.of(1);
        var allowedChecks = new AtomicInteger();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.checkPolicy(
                List.of(new UserMessage("Check synthetic read")), () -> allowedChecks.incrementAndGet() == 1))
                .isInstanceOf(AssistantNativeChat.AccessChanged.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void retriesTheSdkCallTimeout() {
        var attempts = new AtomicInteger();
        String result = GeminiAssistantProvider.requestWithTransientRetry(() -> true, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new GenAiIOException("Failed to execute HTTP request.", new InterruptedIOException("timeout"));
            }
            return "Recovered";
        });
        assertThat(result).isEqualTo("Recovered");
        assertThat(attempts.get()).isEqualTo(2);
    }

    private static final class ExampleRead {
        private int calls;

        @Tool(description = "Read the synthetic example")
        public java.util.Map<String, Object> readExample(int amountMl) {
            calls++;
            return java.util.Map.of("amountMl", amountMl);
        }
    }

    @Test
    void policyCheckUsesToolFreeGoogleOptions() {
        firstParts = "{\"text\":\"{\\\"verdict\\\":\\\"ALLOW\\\",\\\"sources\\\":[]}\"}";
        assertThat(provider.checkPolicy(List.of(new UserMessage("Check synthetic prose")), () -> true))
                .isEqualTo(AssistantScriptedChat.ALLOW);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).doesNotContainKey("tools");
        assertThat(requests.getFirst().get("generationConfig", Document.class).getString("responseMimeType"))
                .isEqualTo("application/json");
    }

    @Test
    void nativeAdvisorVetoesTruncatedOutput() {
        firstParts = "{\"text\":\"Incomplete answer\"}";
        finishReason = "MAX_TOKENS";
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                provider.converse(List.of(new UserMessage("Chào")), List.of(), () -> true, calls -> {}))
                .isInstanceOf(AssistantNativeChat.InvalidResponse.class);
    }

    @Test
    void sendsOneVerifiedSyntheticResultAndPreservesThoughtSignature() {
        assertThat(confirm(CALL, () -> true)).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(checks.get()).isEqualTo(2);
        var first = requests.getFirst();
        assertThat(choice(first).getString("mode")).isEqualTo("ANY");
        assertThat(choice(first).getList("allowedFunctionNames", String.class)).containsExactly("getSleepRecords");
        var declaration = first.getList("tools", Document.class).getFirst()
                .getList("functionDeclarations", Document.class).getFirst();
        assertThat(declaration.getString("name")).isEqualTo("getSleepRecords");
        assertThat(declaration.get("parameters", Document.class).getList("required", String.class))
                .containsExactlyInAnyOrder("from", "to");

        var second = requests.getLast();
        assertThat(choice(second).getString("mode")).isEqualTo("NONE");
        var contents = second.getList("contents", Document.class);
        assertThat(contents).hasSize(3);
        assertThat(contents.get(1).getList("parts", Document.class).getFirst().getString("thoughtSignature"))
                .isEqualTo("dGVzdA==");
        var response = contents.get(2).getList("parts", Document.class).getFirst()
                .get("functionResponse", Document.class);
        assertThat(response.getString("name")).isEqualTo("getSleepRecords");
        assertThat(response.get("response", Document.class))
                .containsOnlyKeys("date", "sleepType", "method", "durationMinutes")
                .containsEntry("date", DAY).containsEntry("sleepType", "MAIN").containsEntry("method", "DURATION");
        assertThat(((Number) response.get("response", Document.class).get("durationMinutes")).intValue())
                .isEqualTo(AssistantFixture.SLEEP_MINUTES);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"text\":\"423\"}",
        "{\"functionCall\":{\"name\":\"unexpected\",\"args\":{\"from\":\"2026-11-18\",\"to\":\"2026-11-18\"}}}",
        "{\"functionCall\":{\"name\":\"getSleepRecords\",\"args\":{\"from\":\"2026-11-17\",\"to\":\"2026-11-18\"}}}",
        "{\"functionCall\":{\"name\":\"getSleepRecords\",\"args\":{\"from\":\"2026-11-18\",\"to\":\"2026-11-18\",\"other\":1}}}"
    })
    void rejectsInvalidToolCalls(String parts) {
        assertThat(confirm(parts, () -> true)).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(checks.get()).isZero();
    }

    @Test
    void rejectsMultipleCalls() {
        assertThat(confirm(CALL + "," + CALL, () -> true)).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(checks.get()).isZero();
    }

    @Test
    void doesNotDispatchWhenPermissionIsMissing() {
        assertThat(confirm(CALL, () -> false)).isFalse();
        assertThat(requests).isEmpty();
        assertThat(checks.get()).isZero();
    }

    @Test
    void doesNotSendToolResultWhenPermissionIsRevoked() {
        var permissionChecks = new AtomicInteger();
        assertThat(confirm(CALL, () -> permissionChecks.incrementAndGet() == 1)).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(checks.get()).isZero();
    }

    private boolean confirm(String parts, BooleanSupplier current) {
        firstParts = parts;
        return provider.confirmsSyntheticSleep(DAY, current, () -> checks.incrementAndGet() > 0);
    }

    private static Document choice(Document request) {
        return request.get("toolConfig", Document.class).get("functionCallingConfig", Document.class);
    }
}
