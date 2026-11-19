package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.water.service.WaterService;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.IntNode;

final class AssistantVoiceActions {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter WATER_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/uuuu");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("mm");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final WaterService water;
    private final SleepJournalService sleep;

    AssistantVoiceActions(WaterService water, SleepJournalService sleep) {
        this.water = water;
        this.sleep = sleep;
    }

    AssistantCourseAnswers.Answer apply(String owner, String transcript, Instant receivedAt, ToolCall call) {
        return apply(owner, List.of(transcript), receivedAt, call);
    }

    AssistantCourseAnswers.Answer apply(String owner, List<String> utterances, Instant receivedAt, ToolCall call) {
        var request = new AssistantVoiceText(utterances, receivedAt.atZone(ZONE).toLocalDate());
        if (call == null) return insufficient(null);
        try {
            return switch (call.name()) {
                case "recordWater" -> recordWater(owner, request, receivedAt, arguments(call, Set.of("amount", "when")));
                case "recordSleep" ->
                        recordSleep(owner, request, arguments(call, Set.of("duration", "day", "start", "end", "feeling")));
                default -> insufficient(null);
            };
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException | JacksonException invalidArguments) {
            return insufficient(call.name());
        } catch (WaterValidationException invalidWater) {
            return answer("INSUFFICIENT_EVIDENCE", "Chưa thể ghi lượng nước. Hãy kiểm tra số ml và thời điểm uống.");
        } catch (SleepFailure rejectedSleep) {
            return sleepFailure(rejectedSleep);
        }
    }

    private AssistantCourseAnswers.Answer recordWater(String owner, AssistantVoiceText request, Instant receivedAt,
            JsonNode args) {
        int amountMl = request.amountMl(request.quote(args, "amount"));
        Instant loggedAt = request.waterTime(request.quote(args, "when"), receivedAt);
        var saved = water.create(owner, amountMl, loggedAt.toString());
        return answer("ANSWERED", "Đã ghi %d ml nước lúc %s."
                .formatted(saved.amountMl(), WATER_TIME.format(saved.loggedAt().atZone(ZONE))));
    }

    private AssistantCourseAnswers.Answer recordSleep(String owner, AssistantVoiceText request, JsonNode args) {
        String feelingQuote = request.quote(args, "feeling");
        if (feelingQuote == null) return insufficient("recordSleep");
        String feeling = request.feeling(feelingQuote);
        String duration = request.quote(args, "duration");
        String day = request.quote(args, "day");
        String start = request.quote(args, "start");
        String end = request.quote(args, "end");
        SessionWrite write;
        if (duration != null && day != null && start == null && end == null) {
            write = new SessionWrite("MAIN", "DURATION", feeling, null, null, null,
                    request.date(day).toString(), IntNode.valueOf(request.minutes(duration)));
        } else if (start != null && end != null && duration == null && day == null) {
            write = new SessionWrite("MAIN", "RANGE", feeling, ZONE.getId(),
                    civilTime(request.dateTime(start, true)), civilTime(request.dateTime(end, true)), null, null);
        } else {
            return insufficient("recordSleep");
        }
        var saved = sleep.create(owner, write);
        return answer("ANSWERED", "Đã ghi %d phút ngủ cho ngày %s, cảm giác %s."
                .formatted(saved.minutes(), saved.day(), request.feelingLabel(feeling)));
    }

    private static SessionWrite.CivilTime civilTime(LocalDateTime time) {
        return new SessionWrite.CivilTime(time.toLocalDate().toString(), HOUR.format(time), MINUTE.format(time), null);
    }

    private static JsonNode arguments(ToolCall call, Set<String> allowed) {
        if (call.arguments() == null || call.arguments().length() > 2048) throw new IllegalArgumentException();
        var args = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
        if (!args.isObject() || !allowed.containsAll(args.propertyNames())) throw new IllegalArgumentException();
        return args;
    }

    private static AssistantCourseAnswers.Answer insufficient(String tool) {
        if ("recordWater".equals(tool)) {
            return answer("INSUFFICIENT_EVIDENCE",
                    "Chưa thể ghi lượng nước. Hãy nói rõ lượng nước theo ml và thời điểm nếu không phải bây giờ.");
        }
        if ("recordSleep".equals(tool)) {
            return answer("INSUFFICIENT_EVIDENCE",
                    "Chưa thể ghi giấc ngủ. Hãy nói rõ ngày và thời lượng, hoặc giờ bắt đầu và kết thúc, cùng cảm nhận.");
        }
        return answer("INSUFFICIENT_EVIDENCE", "Hãy yêu cầu ghi một lần uống nước hoặc một giấc ngủ.");
    }

    private static AssistantCourseAnswers.Answer sleepFailure(SleepFailure failure) {
        String message = switch (failure.code()) {
            case "SLEEP_DAY_ALREADY_RECORDED" -> "Chưa thể ghi giấc ngủ vì ngày này đã có bản ghi.";
            case "SLEEP_OVERLAP" -> "Chưa thể ghi giấc ngủ vì thời gian trùng với một bản ghi đã có.";
            case "SLEEP_WEEK_LOCKED" -> "Chưa thể ghi giấc ngủ vì Nhật ký chỉ nhận bản ghi mới trong tuần hiện tại.";
            default -> "Chưa thể ghi giấc ngủ. Hãy kiểm tra ngày và thời gian ngủ.";
        };
        return answer("INSUFFICIENT_EVIDENCE", message);
    }

    static AssistantCourseAnswers.Answer answer(String status, String message) {
        return new AssistantCourseAnswers.Answer(status, List.of(new AssistantCourseAnswers.Part(status, message, null)), null);
    }
}
