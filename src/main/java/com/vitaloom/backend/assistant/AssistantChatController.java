package com.vitaloom.backend.assistant;

import com.vitaloom.backend.shared.web.ProblemDetails;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("!prod & !production")
@Conditional(AssistantActivation.Gemini.class)
class AssistantChatController {
    private static final List<Citation> CITATIONS = List.of(
            new Citation("app-help-sleep-journal", 1, "Nhật ký giấc ngủ", "/sleep/journal"));
    private final String sandboxAccount;
    private final AssistantChatService chat;
    private final AssistantAccountSettings accountSettings;

    AssistantChatController(@Value("${vitaloom.assistant.sandbox.account-id:${ASSISTANT_SANDBOX_ACCOUNT_ID:}}") String sandboxAccount,
            AssistantChatService chat, AssistantAccountSettings accountSettings) {
        this.sandboxAccount = sandboxAccount.strip();
        this.chat = chat;
        this.accountSettings = accountSettings;
    }

    @PostMapping(path = "/api/assistant/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> answer(Authentication auth, @RequestBody JsonNode body) {
        String owner = auth.getName();
        var preference = accountSettings.requireEnabled(owner);
        if (!sandboxAccount.isEmpty() && !sandboxAccount.equals(auth.getName())) {
            return ProblemDetails.response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied.");
        }

        Request request = parseRequest(body);
        if (request == null) {
            return ProblemDetails.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
        }
        if (!AssistantFixture.matchesQuestion(request.text())) {
            return reply(request.turnId(), "INSUFFICIENT_EVIDENCE",
                    "Trợ lý thử nghiệm hiện chỉ hướng dẫn cách mở Nhật ký giấc ngủ.", List.of());
        }

        try {
            ResponseEntity<?> response = switch (chat.answer(owner, request.turnId(),
                    () -> accountSettings.isCurrent(owner, preference))) {
                case ANSWERED -> reply(request.turnId(), "ANSWERED", AssistantFixture.ANSWER, CITATIONS);
                case DEPENDENCY_UNAVAILABLE -> reply(request.turnId(), "DEPENDENCY_UNAVAILABLE",
                        "Trợ lý tạm thời chưa thể trả lời.", List.of());
                case PENDING -> ProblemDetails.response(HttpStatus.CONFLICT, "ASSISTANT_TURN_PENDING",
                        "An earlier attempt has not been confirmed. Keep the same turn identifier.");
            };
            if (!accountSettings.isCurrent(owner, preference)) {
                throw new AssistantAccountSettingsController.AccountAccessRequiredException();
            }
            return response;
        } catch (DataAccessException failure) {
            return ProblemDetails.response(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE",
                    "The Assistant is temporarily unavailable.");
        }
    }

    private static Request parseRequest(JsonNode body) {
        if (body == null || !body.isObject()
                || !body.path("turnId").isString() || !body.path("text").isString()
                || body.size() != (body.has("consentAccepted") ? 3 : 2)
                || body.has("consentAccepted") && !body.path("consentAccepted").isBoolean()) {
            return null;
        }
        String id = body.path("turnId").asString();
        String text = body.path("text").asString();
        if (id.length() != 36 || text.length() > 300 || text.isBlank()) {
            return null;
        }
        try {
            UUID turnId = UUID.fromString(id);
            return turnId.toString().equalsIgnoreCase(id)
                    ? new Request(turnId, text) : null;
        } catch (IllegalArgumentException invalidUuid) {
            return null;
        }
    }

    private static ResponseEntity<Response> reply(UUID id, String outcome, String answer, List<Citation> citations) {
        return ResponseEntity.ok(new Response(id, outcome, answer, citations));
    }

    private record Request(UUID turnId, String text) {}

    private record Response(UUID turnId, String outcome, String answer, List<Citation> citations) {}

    private record Citation(String id, int version, String title, String route) {}
}
