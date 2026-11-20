package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.IntNode;

final class AssistantJournalTools {
    static final Set<String> NAMES = Set.of("recordWater", "recordSleepDuration", "recordSleepRange");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern CIVIL_MINUTE = Pattern.compile(DATE + "T[0-9]{2}:[0-9]{2}");

    private final String owner;
    private final Instant receivedAt;
    private final AssistantJournalActions actions;
    private final AssistantConversationTurns.WriteExecution execution;
    private final AssistantResponsePolicy policy;
    private final BooleanSupplier current;

    AssistantJournalTools(String owner, Instant receivedAt, AssistantJournalActions actions,
            AssistantConversationTurns.WriteExecution execution, AssistantResponsePolicy policy,
            BooleanSupplier current) {
        this.owner = owner;
        this.receivedAt = receivedAt;
        this.actions = actions;
        this.execution = execution;
        this.policy = policy;
        this.current = current;
    }

    List<ToolCallback> callbacks() {
        return List.of(
                callback("recordWater", "Save one drink explicitly requested by the user. Supply whole ml. "
                        + "Use an ISO offset timestamp only for a clearly stated time; omit for now.",
                        """
                        {"type":"object","additionalProperties":false,"required":["amountMl"],"properties":{
                        "amountMl":{"type":"integer","minimum":1,"maximum":2000},
                        "loggedAt":{"type":"string","description":"ISO offset timestamp"} }}
                        """),
                callback("recordSleepDuration", "Save one MAIN sleep with explicit duration, wake date and feeling.",
                        """
                        {"type":"object","additionalProperties":false,
                        "required":["sleepDate","durationMinutes","feeling"],"properties":{
                        "sleepDate":{"type":"string","description":"ISO local wake date YYYY-MM-DD"},
                        "durationMinutes":{"type":"integer","minimum":1,"maximum":1440},
                        "feeling":{"type":"string","enum":["COMFORTABLE","NORMAL","TIRED"]} }}
                        """),
                callback("recordSleepRange", "Save one MAIN sleep with explicit dated endpoints and feeling.",
                        """
                        {"type":"object","additionalProperties":false,"required":["start","end","feeling"],"properties":{
                        "start":{"type":"string","description":"Local start YYYY-MM-DDTHH:mm in Vietnam"},
                        "end":{"type":"string","description":"Local end YYYY-MM-DDTHH:mm in Vietnam"},
                        "feeling":{"type":"string","enum":["COMFORTABLE","NORMAL","TIRED"]} }}
                        """));
    }

    private ToolCallback callback(String name, String description, String schema) {
        var definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String arguments) { return run(name, arguments); }
        };
    }

    private String run(String name, String arguments) {
        execution.begin();
        AssistantNativeChat.requireCurrent(current);
        PreparedWrite write;
        try {
            if (arguments == null || arguments.length() > 1200) throw new InvalidArgument("arguments");
            var fields = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
            if (!fields.isObject()) throw new InvalidArgument("arguments");
            write = switch (name) {
                case "recordWater" -> water(fields);
                case "recordSleepDuration" -> duration(fields);
                case "recordSleepRange" -> range(fields);
                default -> throw new IllegalStateException("Unregistered journal callback");
            };
        } catch (InvalidArgument invalid) {
            return reject(name, invalid.field);
        } catch (JacksonException invalid) {
            return reject(name, "arguments");
        }
        if (!policy.permitsWrite(name, write.arguments())) return reject(name, "intent");
        var result = execution.executeValidated(write.create());
        return JSON.writeValueAsString(result.toolResult());
    }

    private String reject(String tool, String field) {
        String question = switch (field) {
            case "amountMl" -> "Bạn đã uống bao nhiêu ml?";
            case "loggedAt" -> "Bạn uống nước vào ngày và giờ nào?";
            case "sleepDate" -> "Bạn thức dậy vào ngày nào?";
            case "durationMinutes" -> "Bạn đã ngủ bao lâu?";
            case "feeling" -> "Bạn cảm thấy thế nào khi thức dậy?";
            case "start" -> "Bạn bắt đầu ngủ vào ngày và giờ nào?";
            case "end" -> "Bạn thức dậy vào ngày và giờ nào?";
            default -> "recordWater".equals(tool)
                    ? "Bạn có thể xác nhận lượng nước và thời điểm uống không?"
                    : "Bạn có thể xác nhận ngày, thời gian ngủ và cảm nhận không?";
        };
        String copy = "Trợ lý chưa thể ghi nhật ký. " + question;
        execution.reject(AssistantResponsePolicy.message("INSUFFICIENT_EVIDENCE", copy));
        AssistantNativeChat.requireCurrent(current);
        return JSON.writeValueAsString(Map.of("status", "REJECTED", "code",
                "intent".equals(field) ? "UNCLEAR_INTENT" : "INVALID_ARGUMENTS", "field", field));
    }

    private PreparedWrite water(JsonNode fields) {
        requireOnly(fields, Set.of("amountMl", "loggedAt"));
        int amount = integer(fields, "amountMl", 2000);
        if (!fields.has("loggedAt")) {
            return new PreparedWrite(Map.of("amountMl", amount), () -> actions.saveWater(owner, amount, receivedAt));
        }
        String value = string(fields, "loggedAt");
        Instant time = time("loggedAt", () -> OffsetDateTime.parse(value).toInstant());
        if (time.isAfter(receivedAt)) throw new InvalidArgument("loggedAt");
        return new PreparedWrite(Map.of("amountMl", amount, "loggedAt", value),
                () -> actions.saveWater(owner, amount, time));
    }

    private PreparedWrite duration(JsonNode fields) {
        requireOnly(fields, Set.of("sleepDate", "durationMinutes", "feeling"));
        String date = string(fields, "sleepDate");
        if (!DATE.matcher(date).matches()) throw new InvalidArgument("sleepDate");
        time("sleepDate", () -> LocalDate.parse(date));
        int minutes = integer(fields, "durationMinutes", 1440);
        String feeling = feeling(fields);
        var write = new SessionWrite("MAIN", "DURATION", feeling, null, null, null, date, IntNode.valueOf(minutes));
        return new PreparedWrite(Map.of("sleepDate", date, "durationMinutes", minutes, "feeling", feeling),
                () -> actions.saveSleep(owner, write));
    }

    private PreparedWrite range(JsonNode fields) {
        requireOnly(fields, Set.of("start", "end", "feeling"));
        String from = string(fields, "start");
        String to = string(fields, "end");
        var start = civilMinute("start", from);
        var end = civilMinute("end", to);
        String feeling = feeling(fields);
        var write = new SessionWrite("MAIN", "RANGE", feeling, AssistantJournalActions.ZONE.getId(),
                AssistantJournalActions.civilTime(start), AssistantJournalActions.civilTime(end), null, null);
        return new PreparedWrite(Map.of("start", from, "end", to, "feeling", feeling),
                () -> actions.saveSleep(owner, write));
    }

    private static void requireOnly(JsonNode fields, Set<String> allowed) {
        if (!allowed.containsAll(fields.propertyNames())) throw new InvalidArgument("arguments");
    }

    private static int integer(JsonNode fields, String key, int max) {
        var value = fields.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > max) {
            throw new InvalidArgument(key);
        }
        return value.intValue();
    }

    private static String string(JsonNode fields, String key) {
        var value = fields.path(key);
        if (!value.isString() || value.stringValue().isBlank()) throw new InvalidArgument(key);
        return value.stringValue();
    }

    private static String feeling(JsonNode fields) {
        String feeling = string(fields, "feeling");
        if (!Set.of("COMFORTABLE", "NORMAL", "TIRED").contains(feeling)) throw new InvalidArgument("feeling");
        return feeling;
    }

    private static LocalDateTime civilMinute(String field, String value) {
        if (!CIVIL_MINUTE.matcher(value).matches()) throw new InvalidArgument(field);
        return time(field, () -> LocalDateTime.parse(value));
    }

    private static <T> T time(String field, Supplier<T> parse) {
        try {
            return parse.get();
        } catch (DateTimeException invalid) {
            throw new InvalidArgument(field);
        }
    }

    private record PreparedWrite(Map<String, Object> arguments, Supplier<AssistantJournalActions.Outcome> create) {}

    private static final class InvalidArgument extends RuntimeException {
        private final String field;

        InvalidArgument(String field) { this.field = field; }
    }
}
