package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.exception.SleepFailure;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Preference;
import com.vitaloom.backend.sleep.service.SleepStoryService.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
        rejectParameters(request);
        return noStore(story.story(auth.getName()));
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
