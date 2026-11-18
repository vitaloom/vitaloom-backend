package com.vitaloom.backend.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Conditional(AssistantActivation.Course.class)
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
        return view(settings.set(authentication.getName(), request.personalDataAllowed()));
    }

    private static View view(AssistantAccountSettings.Preference preference) {
        return new View(preference.personalDataAllowed());
    }

    record Change(@NotNull Boolean personalDataAllowed) {}
    record View(boolean personalDataAllowed) {}

    static class AssistantAccountSettingRequiredException extends RuntimeException {}

    static AssistantAccountSettingRequiredException accountSettingRequired() {
        return new AssistantAccountSettingRequiredException();
    }
}
