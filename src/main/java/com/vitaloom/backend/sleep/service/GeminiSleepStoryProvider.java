package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.sleep.service.SleepStoryService.Candidate;
import com.vitaloom.backend.sleep.service.SleepStoryService.CacheNamespacedProvider;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.sleep.service.SleepStoryService.Explanation;
import com.vitaloom.backend.sleep.service.SleepStoryService.Story;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("default & !prod & !production")
@ConditionalOnProperty(prefix = "vitaloom.sleep.gemini", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GeminiSleepStoryProvider implements CacheNamespacedProvider {
    private static final String MODEL = "gemini-3.1-flash-lite";
    private static final URI ENDPOINT = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final long MINUTE_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int MAX_PER_MINUTE = 4;
    private static final int MAX_PER_PROCESS = 12;
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(500, 502, 503, 504);

    private final String apiKey;
    private final Transport transport;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final LongSupplier ticker;
    private final int maxDispatches;
    private final ArrayDeque<Long> dispatches = new ArrayDeque<>();
    private int lifetimeCalls;

    public GeminiSleepStoryProvider(String apiKey, ObjectMapper mapper) {
        this(apiKey, mapper, MAX_PER_PROCESS);
    }

    @Autowired
    public GeminiSleepStoryProvider(@Value("${GEMINI_API_KEY:}") String apiKey, ObjectMapper mapper,
            @Value("${vitaloom.sleep.gemini.max-dispatches:12}") int maxDispatches) {
        this(apiKey, mapper, new HttpTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()), System::nanoTime, maxDispatches);
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client;

        private HttpTransport(HttpClient client) { this.client = client; }

        @Override
        public ProviderResponse send(HttpRequest request, byte[] ignoredBody) throws IOException, InterruptedException {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) return new ProviderResponse(response.statusCode(), new byte[0]);
                byte[] body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Gemini response exceeded its byte limit.");
                return new ProviderResponse(response.statusCode(), body);
            }
        }
    }

    GeminiSleepStoryProvider(String apiKey, ObjectMapper mapper, Transport transport, LongSupplier ticker) {
        this(apiKey, mapper, transport, ticker, MAX_PER_PROCESS);
    }

    GeminiSleepStoryProvider(String apiKey, ObjectMapper mapper, Transport transport, LongSupplier ticker, int maxDispatches) {
        if (apiKey == null || apiKey.isEmpty() || !apiKey.chars().allMatch(c -> c >= '!' && c <= '~')) {
            throw new IllegalStateException("Gemini provider requires a valid GEMINI_API_KEY.");
        }
        if (maxDispatches < 1 || maxDispatches > MAX_PER_PROCESS) {
            throw new IllegalArgumentException("Gemini provider attempt limit must be between 1 and 12.");
        }
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.strictMapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        this.transport = transport;
        this.ticker = ticker;
        this.maxDispatches = maxDispatches;
    }

    @Override
    public Story generate(Evidence evidence) {
        JsonNode result = generateJson("Select one or two insight codes from the supplied READY evidence. "
                + "Select at least one code other than A01. Return only insightCodes.", storySchema(),
                Map.of("readyEvidence", storyCandidates(evidence)));
        List<String> selected = stringArray(result, "insightCodes");
        if (selected.isEmpty() || selected.size() > 2 || new HashSet<>(selected).size() != selected.size()
                || selected.stream().anyMatch(code -> !evidence.candidates().stream().anyMatch(c -> c.code().equals(code)))
                || selected.stream().noneMatch(code -> !code.equals("A01"))) {
            throw new IllegalArgumentException("Gemini returned unsupported story selections.");
        }
        List<SleepStoryService.Observation> observations = selected.stream()
                .map(code -> evidence.candidates().stream().filter(candidate -> candidate.code().equals(code)).findFirst().orElseThrow())
                .map(candidate -> SleepStoryService.approvedObservation(candidate))
                .toList();
        return new Story(SleepStoryService.approvedHeadline(), observations);
    }

    @Override
    public Explanation explain(Evidence evidence) {
        Candidate candidate = evidence.candidates().getFirst();
        JsonNode result = generateJson("Choose framing DIRECT or GENTLE for this READY insight. "
                + "Use DIRECT for a concise explanation or GENTLE for a softer introduction. "
                + "Return the insightCode and framing only. Set insightCode to " + candidate.code() + ".",
                explanationSchema(candidate.code()),
                Map.of("readyEvidence", Map.of(candidate.code(), safeCandidate(candidate))));
        String code = requiredText(result, "insightCode");
        String framing = requiredText(result, "framing");
        if (result.size() != 2 || !candidate.code().equals(code) || !Set.of("DIRECT", "GENTLE").contains(framing)) {
            throw new IllegalArgumentException("Gemini returned unsupported explanation framing.");
        }
        Explanation approved = SleepStoryService.explanationPreview(evidence);
        if ("GENTLE".equals(framing)) {
            return new Explanation(SleepStoryService.gentleExplanation(approved), approved.evidenceBasis(), approved.limitations(),
                    approved.evidenceFingerprint());
        }
        return approved;
    }

    @Override
    public String cacheNamespace() {
        return "gemini:" + MODEL + ":v1";
    }

    @Override
    public boolean sendsEvidenceExternally() { return true; }

    private JsonNode generateJson(String instruction, Map<String, Object> schema, Map<String, Object> input) {
        try {
            String prompt = instruction + " JSON input: " + mapper.writeValueAsString(input);
            Map<String, Object> payload = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "candidateCount", 1,
                            "maxOutputTokens", 128,
                            "thinkingConfig", Map.of("thinkingLevel", "MINIMAL"),
                            "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", schema))));
            byte[] body = mapper.writeValueAsBytes(payload);
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Gemini request exceeded its byte limit.");
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            reserveCall();
            ProviderResponse response = transport.send(request, body);
            int status = response.status();
            if (TRANSIENT_STATUS.contains(status)) {
                throw new SleepStoryService.TransientProviderException("Gemini temporarily unavailable.");
            }
            if (status != 200) throw new IllegalArgumentException("Gemini rejected the request.");
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("Gemini response exceeded its byte limit.");
            }
            return responseText(readStrictJson(response.body()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Gemini request interrupted.");
        } catch (SSLException | ProtocolException rejected) {
            throw new IllegalArgumentException("Gemini transport rejected the request.");
        } catch (IOException failed) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalArgumentException("Gemini request interrupted.");
            }
            throw new SleepStoryService.TransientProviderException("Gemini transport failed.");
        }
    }

    @FunctionalInterface
    public interface Transport {
        ProviderResponse send(HttpRequest request, byte[] requestBody) throws IOException, InterruptedException;
    }

    public record ProviderResponse(int status, byte[] body) {
        public ProviderResponse {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof ProviderResponse response
                    && status == response.status && Arrays.equals(body, response.body);
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(status) + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "ProviderResponse[status=" + status + ", body=<redacted>]";
        }
    }

    private JsonNode responseText(JsonNode response) {
        if (response.path("promptFeedback").has("blockReason")) {
            throw new IllegalArgumentException("Gemini blocked the prompt.");
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) throw new IllegalArgumentException("Gemini response had no single candidate.");
        JsonNode candidate = candidates.get(0);
        if (!candidate.path("finishReason").asText().equals("STOP")) throw new IllegalArgumentException("Gemini response was incomplete.");
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.size() != 1 || !parts.get(0).path("text").isTextual()
                || parts.get(0).path("thought").asBoolean(false)) {
            throw new IllegalArgumentException("Gemini response did not contain one JSON text part.");
        }
        byte[] structuredText = parts.get(0).path("text").asText().getBytes(StandardCharsets.UTF_8);
        if (structuredText.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Gemini structured response exceeded its byte limit.");
        JsonNode structured = readStrictJson(structuredText);
        if (!structured.isObject()) throw new IllegalArgumentException("Gemini response was not a JSON object.");
        return structured;
    }

    private JsonNode readStrictJson(byte[] json) {
        return strictMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
    }

    private synchronized void reserveCall() {
        if (lifetimeCalls >= maxDispatches) throw new IllegalStateException("Gemini provider attempt limit reached.");
        long now = ticker.getAsLong();
        while (!dispatches.isEmpty() && now - dispatches.getFirst() >= MINUTE_NANOS) dispatches.removeFirst();
        if (dispatches.size() >= MAX_PER_MINUTE) throw new IllegalStateException("Gemini provider rate limit reached.");
        dispatches.addLast(now);
        lifetimeCalls++;
    }

    private static Map<String, Object> storyCandidates(Evidence evidence) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Candidate candidate : evidence.candidates()) safe.put(candidate.code(), safeCandidate(candidate));
        return safe;
    }

    private static Map<String, Object> safeCandidate(Candidate candidate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        switch (candidate.code()) {
            case "A01" -> putNumbers(fields, candidate.sample(), "recordedMainDays");
            case "A02" -> {
                putNumbers(fields, candidate.evidence(), "metGoalDays");
                putNumbers(fields, candidate.sample(), "eligibleMainDays");
            }
            case "A03" -> {
                fields.put("baseline", numericFields(nested(candidate.evidence(), "baseline"), "meanMinutes"));
                fields.put("recent", numericFields(nested(candidate.evidence(), "recent"), "meanMinutes"));
                putNumbers(fields, candidate.evidence(), "deltaMinutes");
            }
            case "A04" -> {
                putNumbers(fields, candidate.sample(), "eligibleRangeDays");
                fields.put("bedtime", numericFields(nested(candidate.evidence(), "bedtime"), "spanMinutes"));
                fields.put("wake", numericFields(nested(candidate.evidence(), "wake"), "spanMinutes"));
            }
            case "A05" -> {
                fields.put("weekday", numericFields(nested(candidate.evidence(), "weekday"), "meanMinutes", "days"));
                fields.put("weekend", numericFields(nested(candidate.evidence(), "weekend"), "meanMinutes", "days"));
                putNumbers(fields, candidate.evidence(), "deltaMinutes");
            }
            case "B01" -> {
                putNumbers(fields, candidate.sample(), "ratedMain", "mainWithoutFeeling");
                fields.put("comfortable", numericFields(nested(candidate.evidence(), "comfortable"), "count"));
                fields.put("normal", numericFields(nested(candidate.evidence(), "normal"), "count"));
                fields.put("tired", numericFields(nested(candidate.evidence(), "tired"), "count"));
            }
            case "B02" -> {
                fields.put("comfortable", numericFields(nested(candidate.evidence(), "comfortable"), "meanMinutes", "days"));
                fields.put("tired", numericFields(nested(candidate.evidence(), "tired"), "meanMinutes", "days"));
                putNumbers(fields, candidate.evidence(), "deltaMinutes");
            }
            case "B03" -> {
                fields.put("baseline", numericFields(candidate.sample(), "baselineRatedMain", "baselineMainWithoutFeeling"));
                fields.put("recent", numericFields(candidate.sample(), "recentRatedMain", "recentMainWithoutFeeling"));
                fields.put("deltaPercentagePoints", numericFields(nested(candidate.evidence(), "deltaPercentagePoints"),
                        "comfortable", "normal", "tired"));
            }
            case "B04" -> {
                putNumbers(fields, candidate.evidence(), "exceptionCount");
                putNumbers(fields, candidate.sample(), "ratedMain");
            }
            default -> throw new IllegalArgumentException("Unsupported insight code.");
        }
        return fields;
    }

    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        Object nested = values.get(key);
        if (!(nested instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((name, value) -> { if (name instanceof String string) result.put(string, value); });
        return result;
    }

    private static void putNumbers(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Number) target.put(key, value);
        }
    }

    private static Map<String, Object> numericFields(Map<String, Object> source, String... keys) {
        Map<String, Object> fields = new LinkedHashMap<>();
        putNumbers(fields, source, keys);
        return fields;
    }

    private static List<String> stringArray(JsonNode object, String key) {
        JsonNode array = object.path(key);
        if (!array.isArray()) throw new IllegalArgumentException("Gemini response has an invalid field.");
        List<String> values = new ArrayList<>();
        array.forEach(value -> {
            if (!value.isTextual()) throw new IllegalArgumentException("Gemini response has an invalid field.");
            values.add(value.asText());
        });
        if (object.size() != 1) throw new IllegalArgumentException("Gemini response has unexpected fields.");
        return values;
    }

    private static String requiredText(JsonNode object, String key) {
        JsonNode value = object.path(key);
        if (!value.isTextual()) throw new IllegalArgumentException("Gemini response has an invalid field.");
        return value.asText();
    }

    private static Map<String, Object> storySchema() {
        return Map.of("type", "object", "properties", Map.of("insightCodes", Map.of(
                "type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 2)), "required", List.of("insightCodes"),
                "additionalProperties", false);
    }

    private static Map<String, Object> explanationSchema(String insightCode) {
        return Map.of("type", "object", "properties", Map.of(
                "insightCode", Map.of("type", "string", "enum", List.of(insightCode)),
                "framing", Map.of("type", "string", "enum", List.of("DIRECT", "GENTLE"))),
                "required", List.of("insightCode", "framing"), "additionalProperties", false);
    }
}
