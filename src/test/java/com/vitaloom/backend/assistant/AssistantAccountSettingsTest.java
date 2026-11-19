package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AssistantAccountSettingsTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantAccountSettings settings = new AssistantAccountSettings(mongo);

    @Test
    void settingsEndpointReturnsOnlyThePreferenceForTheAuthenticatedAccount() {
        when(mongo.findById("account-a", AssistantAccountSettings.Preference.class))
                .thenReturn(new AssistantAccountSettings.Preference("account-a", true, 7, AssistantAccountSettings.CONSENT_VERSION));

        var response = new AssistantAccountSettingsController(settings)
                .get(new TestingAuthenticationToken("account-a", ""));

        assertThat(response).isEqualTo(new AssistantAccountSettingsController.View(true));
    }

    @Test
    void accountsWithoutSavedSettingsDefaultToPersonalDataOff() {
        when(mongo.findById("account-a", AssistantAccountSettings.Preference.class)).thenReturn(null);

        var preference = settings.get("account-a");

        assertThat(preference.personalDataAllowed()).isFalse();
        assertThat(preference.generation()).isZero();
    }

    @Test
    void defaultOffSettingIsInvalidatedByFirstOptIn() {
        when(mongo.findById("account-a", AssistantAccountSettings.Preference.class))
                .thenReturn(null, null, new AssistantAccountSettings.Preference("account-a", true, 1, AssistantAccountSettings.CONSENT_VERSION));
        var preference = settings.get("account-a");

        assertThat(settings.isUnchanged("account-a", preference)).isTrue();
        assertThat(settings.isUnchanged("account-a", preference)).isFalse();
    }

    @Test
    void settingIsStoredIndependentlyForTheAuthenticatedAccount() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantAccountSettings.Preference.class)))
                .thenReturn(new AssistantAccountSettings.Preference("account-a", true, 1, AssistantAccountSettings.CONSENT_VERSION));

        var preference = settings.set("account-a", true);

        assertThat(preference.personalDataAllowed()).isTrue();
        assertThat(preference.generation()).isEqualTo(1);
        verify(mongo).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantAccountSettings.Preference.class));
        verify(mongo, never()).findById(eq("account-b"), eq(AssistantAccountSettings.Preference.class));
    }

    @Test
    void legacyUnversionedEnableDoesNotGrantPersonalDataAccess() {
        assertThatThrownBy(() -> new AssistantAccountSettingsController(settings)
                .set(new TestingAuthenticationToken("account-a", ""),
                        new AssistantAccountSettingsController.Change(true, null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantAccountSettings.Preference.class));
    }

    @Test
    void storedLegacyGrantCannotActivateAssistant() {
        var legacy = new AssistantAccountSettings.Preference("account-a", true, 7, null);
        when(mongo.findById("account-a", AssistantAccountSettings.Preference.class)).thenReturn(legacy);

        assertThat(settings.get("account-a").personalDataAllowed()).isFalse();
        assertThatThrownBy(() -> settings.requireEnabled("account-a"))
                .isInstanceOf(AssistantAccountSettingsController.AccountAccessRequiredException.class);
        assertThat(settings.isCurrent("account-a", legacy)).isFalse();
    }

    @Test
    void enablingRequiresTheCurrentConsentVersion() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantAccountSettings.Preference.class)))
                .thenReturn(new AssistantAccountSettings.Preference("account-a", true, 1, AssistantAccountSettings.CONSENT_VERSION));

        var response = new AssistantAccountSettingsController(settings)
                .set(new TestingAuthenticationToken("account-a", ""),
                        new AssistantAccountSettingsController.Change(true, "gemini-health-v1"));

        assertThat(response.personalDataAllowed()).isTrue();
    }

    @Test
    void disabledSettingsCannotBeUsedForProtectedAssistantOperations() {
        when(mongo.findById("account-a", AssistantAccountSettings.Preference.class)).thenReturn(null);

        assertThatThrownBy(() -> settings.requireEnabled("account-a"))
                .isInstanceOf(AssistantAccountSettingsController.AccountAccessRequiredException.class);
    }

    @Test
    void personalAccessBecomesInvalidWhenItsAccountSettingChanges() {
        var allowed = new AssistantAccountSettings.Preference("account-a", true, 1, AssistantAccountSettings.CONSENT_VERSION);
        var revoked = new AssistantAccountSettings.Preference("account-a", false, 2, null);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(AssistantAccountSettings.Preference.class))).thenReturn(allowed, revoked);
        when(mongo.exists(any(Query.class), eq(AssistantAccountSettings.Preference.class)))
                .thenReturn(true, false);

        assertThat(settings.isCurrent("account-a", settings.set("account-a", true))).isTrue();
        assertThat(settings.set("account-a", false)).isEqualTo(revoked);
        assertThat(settings.isCurrent("account-a", revoked)).isFalse();
    }
}
