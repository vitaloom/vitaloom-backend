package com.vitaloom.backend.assistant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AssemblyAiAssistantSpeechToText implements AssistantSpeechToText {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration TRANSCRIPTION_TIMEOUT = Duration.ofSeconds(40);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final Semaphore slots = new Semaphore(2);
    private final String apiKey;
    private final URI base;

    AssemblyAiAssistantSpeechToText(String apiKey, URI base) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ASSEMBLYAI_API_KEY is required when voice logging is enabled.");
        }
        this.apiKey = apiKey.strip();
        this.base = base;
    }

    @Override
    public String transcribe(byte[] audio) {
        if (!slots.tryAcquire()) throw new SpeechTranscriptionException();
        String transcriptPath = null;
        try {
            var upload = send(request("/v2/upload")
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio)).build());
            String audioUrl = upload.path("upload_url").asString("");
            if (!audioUrl.startsWith("https://")) throw new SpeechTranscriptionException();
            String payload = JSON.writeValueAsString(Map.of("audio_url", audioUrl, "language_code", "vi",
                    "speech_models", List.of("universal-3-5-pro", "universal-2")));
            var transcript = send(request("/v2/transcript").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
            String id = transcript.path("id").asString("");
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new SpeechTranscriptionException();
            transcriptPath = "/v2/transcript/" + id;
            return waitForTranscript(transcriptPath, transcript);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpeechTranscriptionException();
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw new SpeechTranscriptionException();
        } finally {
            if (transcriptPath != null) deleteTranscript(transcriptPath);
            slots.release();
        }
    }

    private String waitForTranscript(String path, JsonNode transcript) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TRANSCRIPTION_TIMEOUT.toNanos();
        while (true) {
            switch (transcript.path("status").asString("")) {
                case "completed" -> {
                    var duration = transcript.path("audio_duration");
                    if (duration.isNumber() && duration.asDouble() > 20) throw new SpeechTranscriptionException();
                    return transcript.path("text").asString("").strip();
                }
                case "queued", "processing" -> {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= POLL_INTERVAL.toNanos()) throw new SpeechTranscriptionException();
                    Thread.sleep(POLL_INTERVAL);
                    remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new SpeechTranscriptionException();
                    Duration timeout = Duration.ofNanos(remaining);
                    transcript = send(request(path).timeout(timeout.compareTo(REQUEST_TIMEOUT) < 0
                            ? timeout : REQUEST_TIMEOUT).GET().build());
                }
                default -> throw new SpeechTranscriptionException();
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT).header("Authorization", apiKey);
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new SpeechTranscriptionException();
        return JSON.readTree(response.body());
    }

    private void deleteTranscript(String path) {
        try {
            var response = http.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The cleanup request is best effort.
        }
        LoggerFactory.getLogger(getClass()).warn("AssemblyAI transcript cleanup was not confirmed.");
    }
}
