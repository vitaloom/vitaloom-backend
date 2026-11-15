package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.goal.SleepGoalResponse;
import com.vitaloom.backend.sleep.goal.SleepGoalService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class SleepGoalController {
    private final SleepGoalService goals;

    public SleepGoalController(SleepGoalService goals) { this.goals = goals; }

    @GetMapping("/api/sleep/goal")
    public ResponseEntity<SleepGoalResponse> get(Authentication auth, HttpServletRequest request,
            @RequestParam(required = false) String day) {
        if (request.getParameterMap().keySet().stream().anyMatch(key -> !"day".equals(key))
                || request.getParameterValues("day") != null && request.getParameterValues("day").length != 1) {
            throw new SleepFailure(400, "INVALID_REQUEST", "Unexpected query parameter.");
        }
        LocalDate requestedDay = day == null ? null : parseDay(day);
        return noStore(goals.get(auth.getName(), requestedDay));
    }

    @PutMapping("/api/sleep/goal")
    public ResponseEntity<SleepGoalResponse> set(Authentication auth, HttpServletRequest request,
            @RequestBody(required = false) JsonNode body) {
        rejectExtraParameters(request, 0);
        if (body == null || !body.isObject() || body.size() != 2
                || !body.path("minutes").isIntegralNumber() || !body.path("minutes").canConvertToInt()
                || !body.path("revision").isIntegralNumber() || !body.path("revision").canConvertToLong()
                || body.path("revision").longValue() > 9_007_199_254_740_991L) {
            throw new SleepFailure(400, "INVALID_SLEEP_GOAL", "Integer minutes and revision are required.");
        }
        return noStore(goals.set(auth.getName(), body.path("minutes").intValue(), body.path("revision").longValue()));
    }

    @DeleteMapping("/api/sleep/goal")
    public ResponseEntity<SleepGoalResponse> remove(Authentication auth, HttpServletRequest request,
            @RequestParam Long revision) {
        if (request.getParameterMap().size() != 1 || !request.getParameterMap().containsKey("revision")
                || request.getParameterValues("revision").length != 1) {
            throw new SleepFailure(400, "INVALID_REQUEST", "revision is the only accepted query parameter.");
        }
        return noStore(goals.remove(auth.getName(), revision));
    }

    private static LocalDate parseDay(String day) {
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new SleepFailure(400, "INVALID_SLEEP_GOAL", "day must be a valid YYYY-MM-DD date.", "day");
        }
        try {
            LocalDate parsed = LocalDate.parse(day);
            if (!parsed.toString().equals(day)) throw new DateTimeException("non-canonical");
            return parsed;
        } catch (DateTimeException invalid) {
            throw new SleepFailure(400, "INVALID_SLEEP_GOAL", "day must be a valid YYYY-MM-DD date.", "day");
        }
    }

    private static void rejectExtraParameters(HttpServletRequest request, int allowedCount) {
        if (request.getParameterMap().size() != allowedCount) {
            throw new SleepFailure(400, "INVALID_REQUEST", "Unexpected query parameter.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
