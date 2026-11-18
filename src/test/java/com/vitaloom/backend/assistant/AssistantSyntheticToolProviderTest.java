package com.vitaloom.backend.assistant;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
                String parts = index == 1 ? firstParts : "{\"text\":\"423\"}";
                response = ("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + parts
                        + "]},\"finishReason\":\"STOP\"}],\"modelVersion\":\"gemini-3.1-flash-lite\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var client = Client.builder().vertexAI(false).apiKey("synthetic-test-key")
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .build();
        provider = new GeminiAssistantProvider(client, "gemini-3.1-flash-lite");
    }

    @AfterEach
    void stop() {
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
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
