package com.vitaloom.backend.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import com.vitaloom.backend.auth.exception.UserNotFoundException;
import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.profile.dto.ProfileRequests;
import com.vitaloom.backend.profile.dto.ProfileResponses;
import com.vitaloom.backend.profile.repository.HealthProfileRepository;
import com.vitaloom.backend.profile.service.impl.HealthProfileServiceImpl;
import org.junit.jupiter.api.Test;

class HealthProfileServiceTest {
    private static final String USER_ID = "user-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);

    private final HealthProfileRepository profiles = mock(HealthProfileRepository.class);
    private final HealthProfileServiceImpl service = new HealthProfileServiceImpl(profiles, CLOCK);

    @Test
    void readsVerifiedEmptyStateAndMatchingAttemptReceipt() {
        User empty = userWithProfile(null);
        Map<String, Object> receipt = Map.of("attemptId", "attempt-1", "kind", "partial-success",
                "applied", java.util.List.of("weightKg"), "errors", Map.of("heightCm", "INVALID_VALUE"));
        User saved = userWithProfile(profile("r1", Map.of("weightKg", 67.0), receipt));
        when(profiles.findUser(USER_ID)).thenReturn(empty, saved, saved);

        ProfileResponses.Result emptyRead = service.read(USER_ID, null);

        assertThat(emptyRead.snapshot().profile()).isNull();
        assertThat(emptyRead.snapshot().revision()).isEqualTo("0");
        assertThat(emptyRead.attempt()).isNull();

        ProfileResponses.Result withReceipt = service.read(USER_ID, "attempt-1");

        assertThat(withReceipt.snapshot().revision()).isEqualTo("r1");
        Map<?, ?> returnedReceipt = (Map<?, ?>) withReceipt.attempt();
        assertThat(returnedReceipt.get("kind")).isEqualTo("partial-success");
        assertThat(returnedReceipt.get("applied")).isEqualTo(java.util.List.of("weightKg"));
        assertThat(returnedReceipt.get("errors")).isEqualTo(Map.of("heightCm", "INVALID_VALUE"));
        assertThat(service.read(USER_ID, "other-attempt").attempt()).isNull();
    }

    @Test
    void readRequiresAnExistingAuthenticatedAccount() {
        when(profiles.findUser(USER_ID)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.read(USER_ID, null))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createValidatesEverySupportedFieldAndNormalizesValuesBeforeWriting() {
        User saved = mock(User.class);
        givenUserProfile(null);
        when(profiles.createIfAbsent(eq(USER_ID), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> created = new LinkedHashMap<>(invocation.getArgument(1));
            when(saved.getHealthProfile()).thenReturn(created);
            return saved;
        });

        ProfileResponses.Result result = service.create(USER_ID, new ProfileRequests.Create(Map.of(
                "displayName", "  Linh Anh ", "birthYear", "2001", "gender", "prefer_not_to_say",
                "heightCm", "170,5", "weightKg", "60.25", "activityLevel", "lightly_active"), "attempt-1"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.snapshot().profile().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "displayName", "Linh Anh", "birthYear", 2001, "gender", "prefer_not_to_say",
                "heightCm", 170.5, "weightKg", 60.25, "activityLevel", "lightly_active"));
        assertThat(result.snapshot().revision()).isNotBlank();
        assertThat(result.snapshot().profile().updatedAt()).isEqualTo(CLOCK.instant().toString());
        verify(profiles).createIfAbsent(eq(USER_ID), anyMap());
    }

    @Test
    void createAcceptsInclusiveMinimumAndMaximumFieldBounds() {
        User saved = mock(User.class);
        givenUserProfile(null);
        when(profiles.createIfAbsent(eq(USER_ID), anyMap())).thenAnswer(invocation -> {
            when(saved.getHealthProfile()).thenReturn(new LinkedHashMap<>(invocation.getArgument(1)));
            return saved;
        });

        ProfileResponses.Result result = service.create(USER_ID, new ProfileRequests.Create(Map.of(
                "birthYear", 1900, "heightCm", 20, "weightKg", "0.3"), "lower-bounds"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.snapshot().profile().values()).containsEntry("birthYear", 1900)
                .containsEntry("heightCm", 20.0).containsEntry("weightKg", 0.3);
    }

    @Test
    void createRejectsMalformedScalarTypes() {
        givenUserProfile(null);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", 12);
        fields.put("birthYear", true);
        fields.put("gender", 1);
        fields.put("heightCm", false);
        fields.put("weightKg", true);
        fields.put("activityLevel", 2);

        ProfileResponses.Result result = service.create(USER_ID, new ProfileRequests.Create(fields, "bad-types"));

        assertThat(result.kind()).isEqualTo("validation-error");
        assertThat(result.errors()).containsKeys("displayName", "birthYear", "gender", "heightCm", "weightKg", "activityLevel");
        verify(profiles, never()).createIfAbsent(eq(USER_ID), anyMap());
    }

    @Test
    void createRejectsInvalidValuesAndOversizedAttemptIdsWithoutWriting() {
        givenUserProfile(null);

        ProfileResponses.Result invalid = service.create(USER_ID, new ProfileRequests.Create(Map.of(
                "displayName", " ", "birthYear", "2027", "gender", "unset",
                "heightCm", "19.9", "weightKg", "500.1", "activityLevel", "very"), "attempt"));
        ProfileResponses.Result longAttempt = service.create(USER_ID, new ProfileRequests.Create(
                Map.of("displayName", "Valid"), "x".repeat(129)));

        assertThat(invalid.kind()).isEqualTo("validation-error");
        assertThat(invalid.errors()).containsKeys("displayName", "birthYear", "gender", "heightCm", "weightKg", "activityLevel");
        assertThat(longAttempt.kind()).isEqualTo("validation-error");
        assertThat(longAttempt.errors()).containsEntry("attemptId", "INVALID_VALUE");
        verify(profiles, never()).createIfAbsent(eq(USER_ID), anyMap());
    }

    @Test
    void createTreatsEmptyInputAsSkipAndPreservesAnAlreadyCompletedInvitation() {
        User initiallyEmpty = userWithProfile(null);
        User existing = userWithProfile(profile("existing", Map.of("displayName", "Saved")));
        when(profiles.findUser(USER_ID)).thenReturn(initiallyEmpty, initiallyEmpty, existing);
        when(profiles.decide(USER_ID, "skipped")).thenReturn("skipped", "completed");

        ProfileResponses.Result skipped = service.create(USER_ID, new ProfileRequests.Create(Map.of(), "empty"));
        ProfileResponses.Result existingDecision = service.create(USER_ID, null);

        assertThat(skipped.kind()).isEqualTo("skipped");
        assertThat(existingDecision.kind()).isEqualTo("conflict");
        assertThat(existingDecision.snapshot().revision()).isEqualTo("existing");
        assertThat(existingDecision.snapshot().profile().values()).containsEntry("displayName", "Saved");
        verify(profiles, never()).createIfAbsent(eq(USER_ID), anyMap());
    }

    @Test
    void duplicateCreateReturnsTheConcurrentProfileWithoutReplayingWrite() {
        User empty = userWithProfile(null);
        User winner = userWithProfile(profile("winner", Map.of("displayName", "Saved elsewhere")));
        when(profiles.findUser(USER_ID)).thenReturn(empty, winner);
        when(profiles.createIfAbsent(eq(USER_ID), anyMap())).thenReturn(null);

        ProfileResponses.Result result = service.create(USER_ID,
                new ProfileRequests.Create(Map.of("displayName", "My value"), "attempt-1"));

        assertThat(result.kind()).isEqualTo("conflict");
        assertThat(result.snapshot().revision()).isEqualTo("winner");
        assertThat(result.snapshot().profile().values()).containsEntry("displayName", "Saved elsewhere");
        verify(profiles, times(1)).createIfAbsent(eq(USER_ID), anyMap());
    }

    @Test
    void updateSavesValidFieldsAndReportsInvalidFieldsInPartialResult() {
        User initial = userWithProfile(profile("r1", Map.of("displayName", "Old", "weightKg", 60.0)));
        User saved = userWithProfile(profile("r2", Map.of("displayName", "New", "weightKg", 60.0)));
        when(profiles.findUser(USER_ID)).thenReturn(initial);
        when(profiles.updateIfRevision(eq(USER_ID), eq("r1"), anyMap())).thenReturn(saved);

        ProfileResponses.Result result = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("displayName", " New ", "heightCm", "301"), "r1", "attempt-1"));

        assertThat(result.kind()).isEqualTo("partial-success");
        assertThat(result.snapshot().profile().values()).containsEntry("displayName", "New")
                .containsEntry("weightKg", 60.0);
        assertThat(result.applied()).containsExactly("displayName");
        assertThat(result.errors()).containsEntry("heightCm", "INVALID_VALUE");
        verify(profiles).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void updateClearsFieldsAndAllowsSavingAnExplicitlyEmptyProfile() {
        User initial = userWithProfile(profile("r1", Map.of("displayName", "Name", "weightKg", 60.0)));
        User cleared = userWithProfile(profile("r2", Map.of()));
        when(profiles.findUser(USER_ID)).thenReturn(initial);
        when(profiles.updateIfRevision(eq(USER_ID), eq("r1"), anyMap())).thenReturn(cleared);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("displayName", null);
        patch.put("weightKg", null);
        ProfileResponses.Result result = service.update(USER_ID, new ProfileRequests.Update(patch, "r1", "clear"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.snapshot().profile().values()).isEmpty();
        verify(profiles).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void updateReturnsNoChangesForEqualValuesAndForAnEmptyPatch() {
        givenUserProfile(profile("r1", Map.of("weightKg", 60.0)));

        ProfileResponses.Result equal = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("weightKg", "60.0"), "r1", "equal"));
        ProfileResponses.Result empty = service.update(USER_ID,
                new ProfileRequests.Update(Map.of(), "r1", "empty"));

        assertThat(equal.kind()).isEqualTo("no-changes");
        assertThat(empty.kind()).isEqualTo("no-changes");
        verify(profiles, never()).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void updateRejectsInvalidOnlyUnknownAndMalformedRequestsWithoutWriting() {
        givenUserProfile(profile("r1", Map.of()));

        ProfileResponses.Result invalid = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("weightKg", "0.2"), "r1", "invalid"));
        ProfileResponses.Result unknown = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("unknown", "x"), "r1", "unknown"));
        ProfileResponses.Result missingPatch = service.update(USER_ID, null);
        ProfileResponses.Result missingRevision = service.update(USER_ID,
                new ProfileRequests.Update(Map.of(), null, "missing"));

        assertThat(invalid.kind()).isEqualTo("validation-error");
        assertThat(invalid.errors()).containsEntry("weightKg", "INVALID_VALUE");
        assertThat(unknown.kind()).isEqualTo("validation-error");
        assertThat(unknown.errors()).containsEntry("patch", "INVALID_VALUE");
        assertThat(missingPatch.kind()).isEqualTo("validation-error");
        assertThat(missingRevision.errors()).isEmpty();
        verify(profiles, never()).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void updateAcceptsInclusiveUpperBoundsAndNormalizesNumericInputs() {
        User initial = userWithProfile(profile("r1", Map.of()));
        Map<String, Object> savedValues = Map.of("birthYear", 2026, "heightCm", 300.0, "weightKg", 500.0);
        User saved = userWithProfile(profile("r2", savedValues));
        when(profiles.findUser(USER_ID)).thenReturn(initial);
        when(profiles.updateIfRevision(eq(USER_ID), eq("r1"), anyMap())).thenReturn(saved);

        ProfileResponses.Result result = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("birthYear", "2026", "heightCm", "300", "weightKg", "500.0"), "r1", "upper-bounds"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.snapshot().profile().values()).containsExactlyInAnyOrderEntriesOf(savedValues);
    }

    @Test
    void updateCreatesTheFirstProfileFromVerifiedAbsence() {
        User empty = userWithProfile(null);
        User saved = userWithProfile(profile("created", Map.of("heightCm", 170.0)));
        when(profiles.findUser(USER_ID)).thenReturn(empty);
        when(profiles.updateIfRevision(eq(USER_ID), eq("0"), anyMap())).thenReturn(saved);

        ProfileResponses.Result result = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("heightCm", "170"), "0", "first-profile"));

        assertThat(result.kind()).isEqualTo("success");
        assertThat(result.snapshot().revision()).isEqualTo("created");
        assertThat(result.snapshot().profile().values()).containsEntry("heightCm", 170.0);
        verify(profiles).updateIfRevision(eq(USER_ID), eq("0"), anyMap());
    }

    @Test
    void updateDetectsStaleRevisionBeforeValidatingOrWritingPatch() {
        givenUserProfile(profile("r2", Map.of("displayName", "Winner")));

        ProfileResponses.Result result = service.update(USER_ID,
                new ProfileRequests.Update(Map.of("heightCm", "invalid"), "r1", "stale"));

        assertThat(result.kind()).isEqualTo("conflict");
        assertThat(result.snapshot().revision()).isEqualTo("r2");
        assertThat(result.snapshot().profile().values()).containsEntry("displayName", "Winner");
        verify(profiles, never()).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void updateRejectsOversizedAttemptOnlyAfterFindingAChange() {
        givenUserProfile(profile("r1", Map.of()));

        ProfileResponses.Result changed = service.update(USER_ID, new ProfileRequests.Update(
                Map.of("displayName", "New"), "r1", "x".repeat(129)));
        ProfileResponses.Result unchanged = service.update(USER_ID, new ProfileRequests.Update(
                Map.of(), "r1", "x".repeat(129)));

        assertThat(changed.kind()).isEqualTo("validation-error");
        assertThat(changed.errors()).containsEntry("attemptId", "INVALID_VALUE");
        assertThat(unchanged.kind()).isEqualTo("no-changes");
        verify(profiles, never()).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void invitationDecisionsValidateAndPersistSkippedOrCompletedState() {
        givenUserProfile(profile("r1", Map.of()));
        when(profiles.decide(USER_ID, "skipped")).thenReturn("skipped");
        when(profiles.decide(USER_ID, "completed")).thenReturn("completed");

        ProfileResponses.Result skipped = service.decide(USER_ID, new ProfileRequests.Invitation("skipped"));
        ProfileResponses.Result completed = service.decide(USER_ID, new ProfileRequests.Invitation("completed"));
        ProfileResponses.Result invalid = service.decide(USER_ID, new ProfileRequests.Invitation("pending"));
        ProfileResponses.Result missing = service.decide(USER_ID, null);

        assertThat(skipped.decision()).isEqualTo("skipped");
        assertThat(completed.decision()).isEqualTo("completed");
        assertThat(invalid.errors()).containsEntry("decision", "INVALID_VALUE");
        assertThat(missing.errors()).containsEntry("decision", "INVALID_VALUE");
        verify(profiles).decide(USER_ID, "skipped");
        verify(profiles).decide(USER_ID, "completed");
    }

    @Test
    void completedInvitationRequiresAnExistingProfileAndMissingAccountsAreRejected() {
        givenUserProfile(null);
        when(profiles.decide(USER_ID, "completed")).thenReturn("pending");

        ProfileResponses.Result incomplete = service.decide(USER_ID, new ProfileRequests.Invitation("completed"));
        assertThat(incomplete.errors()).containsEntry("decision", "PROFILE_REQUIRED");

        when(profiles.decide(USER_ID, "skipped")).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.decide(USER_ID, new ProfileRequests.Invitation("skipped")))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void existingProfileRaceReturnsWinnerSnapshotWithoutRetryingWrite() {
        User initial = userWithProfile(profile("r1", Map.of("displayName", "Before")));
        User winner = userWithProfile(profile("r2", Map.of("displayName", "Winner")));
        when(profiles.findUser(USER_ID)).thenReturn(initial, winner);
        when(profiles.updateIfRevision(eq(USER_ID), eq("r1"), anyMap())).thenReturn(null);

        ProfileResponses.Result result = service.update(USER_ID,
                new ProfileRequests.Update(Map.of("displayName", "My edit"), "r1", "attempt-1"));

        assertThat(result.kind()).isEqualTo("conflict");
        assertThat(result.snapshot().revision()).isEqualTo("r2");
        assertThat(result.snapshot().profile().values()).containsEntry("displayName", "Winner");
        verify(profiles, times(1)).updateIfRevision(eq(USER_ID), eq("r1"), anyMap());
    }

    @Test
    void absentProfileRaceReturnsCreatedWinnerWithoutRetryingInsert() {
        User initiallyEmpty = userWithProfile(null);
        User winner = userWithProfile(profile("created-race", Map.of("weightKg", 68.0)));
        when(profiles.findUser(USER_ID)).thenReturn(initiallyEmpty, winner);
        when(profiles.updateIfRevision(eq(USER_ID), eq("0"), anyMap())).thenReturn(null);

        ProfileResponses.Result result = service.update(USER_ID,
                new ProfileRequests.Update(Map.of("heightCm", "170"), "0", "attempt-2"));

        assertThat(result.kind()).isEqualTo("conflict");
        assertThat(result.snapshot().revision()).isEqualTo("created-race");
        assertThat(result.snapshot().profile().values()).containsEntry("weightKg", 68.0);
        verify(profiles, times(1)).updateIfRevision(eq(USER_ID), eq("0"), anyMap());
    }

    private static User userWithProfile(Map<String, Object> healthProfile) {
        User user = mock(User.class);
        when(user.getHealthProfile()).thenReturn(healthProfile);
        return user;
    }

    private void givenUserProfile(Map<String, Object> healthProfile) {
        User user = userWithProfile(healthProfile);
        when(profiles.findUser(USER_ID)).thenReturn(user);
    }

    private static Map<String, Object> profile(String revision, Map<String, Object> values) {
        return profile(revision, values, null);
    }

    private static Map<String, Object> profile(String revision, Map<String, Object> values, Map<String, Object> receipt) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", "profile-1");
        profile.put("values", values);
        profile.put("updatedAt", CLOCK.instant().toString());
        profile.put("revision", revision);
        if (receipt != null) profile.put("lastAttempt", receipt);
        return profile;
    }

}
