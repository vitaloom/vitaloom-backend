package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssemblyAiAssistantSpeechToTextTest {
    @Test
    void uploadsBinaryPollsVietnameseTranscriptAndRequestsDeletion() throws Exception {
        var audio = new byte[] {1, 2, 3};
        var calls = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var bytes = exchange.getRequestBody().readAllBytes();
                String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                calls.add(call);
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("fixture-key");
                String response = switch (call) {
                    case "POST /v2/upload" -> {
                        assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/octet-stream");
                        assertThat(bytes).isEqualTo(audio);
                        yield "{\"upload_url\":\"https://example.test/fixture\"}";
                    }
                    case "POST /v2/transcript" -> {
                        var json = new ObjectMapper().readTree(bytes);
                        assertThat(json.path("language_code").asString()).isEqualTo("vi");
                        assertThat(json.path("speech_models").toString()).isEqualTo("[\"universal-3-5-pro\",\"universal-2\"]");
                        assertThat(json.path("audio_url").asString()).isEqualTo("https://example.test/fixture");
                        yield "{\"id\":\"fixture\",\"status\":\"queued\"}";
                    }
                    case "GET /v2/transcript/fixture" ->
                            "{\"status\":\"completed\",\"text\":\" Ghi 250 ml nước \",\"audio_duration\":3}";
                    case "DELETE /v2/transcript/fixture" -> "{}";
                    default -> throw new AssertionError(call);
                };
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            assertThat(adapter(server).transcribe(audio)).isEqualTo("Ghi 250 ml nước");
            assertThat(calls).containsExactlyElementsOf(List.of("POST /v2/upload", "POST /v2/transcript",
                    "GET /v2/transcript/fixture", "DELETE /v2/transcript/fixture"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureExposesNoResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/upload", exchange -> {
            try (exchange) {
                var body = "credential or sensitive provider detail".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> adapter(server).transcribe(new byte[] {1}))
                    .isInstanceOf(AssistantSpeechToText.SpeechTranscriptionException.class)
                    .hasMessage("Speech transcription is unavailable.");
        } finally {
            server.stop(0);
        }
    }

    private static AssemblyAiAssistantSpeechToText adapter(HttpServer server) {
        return new AssemblyAiAssistantSpeechToText("fixture-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
}
