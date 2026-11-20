package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.water.exception.WaterLogDuplicateException;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

final class AssistantJournalActions {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter WATER_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/uuuu");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("mm");

    private final WaterService water;
    private final SleepJournalService sleep;

    AssistantJournalActions(WaterService water, SleepJournalService sleep) {
        this.water = water;
        this.sleep = sleep;
    }

    Outcome saveWater(String owner, int amountMl, Instant loggedAt) {
        try {
            var saved = water.create(owner, amountMl, loggedAt.toString());
            return new Outcome(AssistantResponsePolicy.message("ANSWERED", "Đã ghi %d ml nước lúc %s."
                    .formatted(saved.amountMl(), WATER_TIME.format(saved.loggedAt().atZone(ZONE)))),
                    Map.of("status", "SAVED", "entry", "WATER", "amountMl", saved.amountMl(),
                            "loggedAt", saved.loggedAt().toString()));
        } catch (WaterLogDuplicateException duplicate) {
            return rejected("WATER_TIMESTAMP_ALREADY_RECORDED",
                    "Chưa thể ghi lượng nước vì thời điểm này đã có bản ghi.");
        } catch (WaterValidationException invalid) {
            return rejected("INVALID_ARGUMENTS", "Chưa thể ghi lượng nước. Hãy kiểm tra số ml và thời điểm uống.");
        }
    }

    Outcome saveSleep(String owner, SessionWrite write) {
        try {
            var saved = sleep.create(owner, write);
            String feeling = switch (saved.feeling()) {
                case "COMFORTABLE" -> "tốt";
                case "NORMAL" -> "bình thường";
                case "TIRED" -> "mệt mỏi";
                default -> throw new IllegalStateException("Unexpected saved sleep feeling");
            };
            return new Outcome(AssistantResponsePolicy.message("ANSWERED", "Đã ghi %d phút ngủ cho ngày %s, cảm giác %s."
                    .formatted(saved.minutes(), saved.day(), feeling)),
                    Map.of("status", "SAVED", "entry", "MAIN_SLEEP", "sleepDate", saved.day(),
                            "durationMinutes", saved.minutes(), "feeling", saved.feeling()));
        } catch (SleepFailure rejected) {
            return new Outcome(sleepFailure(rejected), Map.of("status", "REJECTED", "code", rejected.code()));
        }
    }

    static Outcome rejected(String code, String text) {
        return new Outcome(AssistantResponsePolicy.message("INSUFFICIENT_EVIDENCE", text), Map.of("status", "REJECTED", "code", code));
    }

    record Outcome(AssistantCourseAnswers.Answer answer, Map<String, Object> toolResult) {}

    static SessionWrite.CivilTime civilTime(LocalDateTime time) {
        return new SessionWrite.CivilTime(time.toLocalDate().toString(), HOUR.format(time), MINUTE.format(time), null);
    }

    private static AssistantCourseAnswers.Answer sleepFailure(SleepFailure failure) {
        String message = switch (failure.code()) {
            case "SLEEP_DAY_ALREADY_RECORDED" -> "Chưa thể ghi giấc ngủ vì ngày này đã có bản ghi.";
            case "SLEEP_OVERLAP" -> "Chưa thể ghi giấc ngủ vì thời gian trùng với một bản ghi đã có.";
            case "SLEEP_WEEK_LOCKED" -> "Chưa thể ghi giấc ngủ vì Nhật ký chỉ nhận bản ghi mới trong tuần hiện tại.";
            default -> "Chưa thể ghi giấc ngủ. Hãy kiểm tra ngày và thời gian ngủ.";
        };
        return AssistantResponsePolicy.message("INSUFFICIENT_EVIDENCE", message);
    }
}
