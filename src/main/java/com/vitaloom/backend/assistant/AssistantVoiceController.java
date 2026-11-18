package com.vitaloom.backend.assistant;

import com.vitaloom.backend.shared.web.ProblemDetails;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Conditional({AssistantActivation.Course.class, AssistantActivation.Gemini.class})
@ConditionalOnProperty(name = "ASSISTANT_VOICE_ENABLED", havingValue = "true")
class AssistantVoiceController {
    private static final int MAX_AUDIO_BYTES = 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of(
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/webm", "audio/wav", "audio/x-wav");
    private final AssistantVoiceTurns turns;

    AssistantVoiceController(AssistantVoiceTurns turns) {
        this.turns = turns;
    }

    @PostMapping(path = "/api/assistant/mvp/voice-turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AssistantCourseController.View> send(Authentication authentication,
            @RequestParam UUID turnId, @RequestParam String consentAccepted,
            @RequestParam(required = false) String consentVersion, @RequestPart MultipartFile audio)
            throws IOException {
        if (!"true".equals(consentAccepted) || !"assemblyai-us-v1".equals(consentVersion)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (audio.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (audio.getSize() > MAX_AUDIO_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        String contentType = audio.getContentType();
        if (contentType == null || !MEDIA_TYPES.contains(contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        var conversation = turns.send(authentication.getName(), turnId, audio.getBytes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(conversation);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> storeUnavailable() {
        return ProblemDetails.response(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE",
                "The Assistant is temporarily unavailable.");
    }
}
