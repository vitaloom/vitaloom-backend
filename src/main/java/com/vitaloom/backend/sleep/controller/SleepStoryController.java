package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Preference;
import com.vitaloom.backend.sleep.service.SleepStoryService.Response;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class SleepStoryController {
    private final SleepStoryService story;

    public SleepStoryController(SleepStoryService story) {
        this.story = story;
    }

    @GetMapping("/api/sleep/ai/consent")
    public ResponseEntity<Preference> consent(Authentication auth, HttpServletRequest request) {
        rejectParameters(request);
        return noStore(story.preference(auth.getName()));
    }

    @PutMapping("/api/sleep/ai/consent")
    public ResponseEntity<Preference> consent(Authentication auth, HttpServletRequest request,
            @RequestBody(required = false) JsonNode body) {
        rejectParameters(request);
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("enabled").isBoolean()) {
            throw new SleepFailure(400, "INVALID_REQUEST", "A boolean enabled field is required.");
        }
        return noStore(story.updatePreference(auth.getName(), body.path("enabled").asBoolean()));
    }

    @GetMapping("/api/sleep/recent/story")
    public ResponseEntity<Response> recentStory(Authentication auth, HttpServletRequest request) {
        if (!Set.of("timeZone").containsAll(request.getParameterMap().keySet())) {
            throw new SleepFailure(400, "INVALID_REQUEST", "Only an optional timeZone is accepted.");
        }
        String[] zones = request.getParameterValues("timeZone");
        if (zones != null && zones.length != 1) {
            throw new SleepFailure(400, "INVALID_REQUEST", "A single timeZone is required.");
        }
        return noStore(story.story(auth.getName(), zones == null ? null : selectedZone(zones[0])));
    }

    @PostMapping("/api/sleep/ai/explanations")
    public ResponseEntity<SleepStoryService.ExplanationResponse> explanation(Authentication auth,
            HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        rejectParameters(request);
        if (body == null || !body.isObject() || !body.path("insightCode").isTextual()
                || !SleepStoryService.SUPPORTED_CODES.contains(body.path("insightCode").asText())
                || body.size() != 1 + (body.has("evidenceVersion") ? 1 : 0) + (body.has("timeZone") ? 1 : 0)
                || (body.has("evidenceVersion") && !body.path("evidenceVersion").isTextual())
                || (body.has("timeZone") && (!"A04".equals(body.path("insightCode").asText())
                        || !body.path("timeZone").isTextual()))) {
            throw new SleepFailure(400, "INVALID_REQUEST", "A supported insightCode and optional evidenceVersion are accepted.");
        }
        return noStore(story.explanation(auth.getName(), body.path("insightCode").asText(),
                body.has("evidenceVersion") ? body.path("evidenceVersion").asText() : null,
                body.has("timeZone") ? selectedZone(body.path("timeZone").asText()) : null));
    }

    private static ZoneId selectedZone(String id) {
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new SleepFailure(400, "INVALID_REQUEST", "Select a valid IANA timeZone.");
        }
        return ZoneId.of(id);
    }

    private static void rejectParameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new SleepFailure(400, "INVALID_REQUEST", "Sleep Story does not accept query parameters.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
