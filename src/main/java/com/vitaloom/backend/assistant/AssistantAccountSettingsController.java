package com.vitaloom.backend.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant/mvp/settings")
class AssistantAccountSettingsController {
    private final AssistantAccountSettings settings;

    AssistantAccountSettingsController(AssistantAccountSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    View get(Authentication authentication) {
        return view(settings.get(authentication.getName()));
    }

    @PutMapping
    View set(Authentication authentication, @Valid @RequestBody Change request) {
        if (Boolean.TRUE.equals(request.personalDataAllowed())
                && !AssistantAccountSettings.CONSENT_VERSION.equals(request.consentVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assistant consent disclosure is required.");
        }
        return view(settings.set(authentication.getName(), request.personalDataAllowed()));
    }

    private static View view(AssistantAccountSettings.Preference preference) {
        return new View(preference.personalDataAllowed());
    }

    record Change(@NotNull Boolean personalDataAllowed, String consentVersion) {}
    record View(boolean personalDataAllowed) {}

    static class AssistantAccountSettingRequiredException extends RuntimeException {}

    static class AccountAccessRequiredException extends ResponseStatusException {
        AccountAccessRequiredException() {
            super(HttpStatus.FORBIDDEN, "Enable personal-data access in Assistant account settings.");
        }
    }

    static AssistantAccountSettingRequiredException accountSettingRequired() {
        return new AssistantAccountSettingRequiredException();
    }
}
