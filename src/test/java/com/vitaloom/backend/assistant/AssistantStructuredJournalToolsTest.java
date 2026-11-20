package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AssistantStructuredJournalToolsTest {
    private static final String OWNER = "synthetic-account";
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private final WaterService water = mock(WaterService.class);
    private final SleepJournalService sleep = mock(SleepJournalService.class);
    private final AssistantResponsePolicy policy = mock(AssistantResponsePolicy.class);
    private final AssistantConversationTurns.WriteExecution execution = mock(AssistantConversationTurns.WriteExecution.class);
    private final AssistantJournalTools tools = new AssistantJournalTools(OWNER, NOW,
            new AssistantJournalActions(water, sleep), execution, policy, () -> true);

    @BeforeEach
    void allowExplicitIntent() {
        when(policy.permitsWrite(any(), any())).thenReturn(true);
        when(execution.executeValidated(any())).thenAnswer(invocation -> {
            Supplier<?> create = invocation.getArgument(0);
            return create.get();
        });
    }

    @Test
    void defaultsWaterTimeToTrustedReceiveTimeAndReturnsTheSavedAmount() {
        when(water.create(OWNER, 250, NOW.toString())).thenReturn(new WaterLogResponse("private-id", 245, NOW));

        assertThat(call("recordWater", "{\"amountMl\":250}"))
                .contains("\"status\":\"SAVED\"", "\"amountMl\":245", NOW.toString()).doesNotContain("private-id");
        verify(water).create(OWNER, 250, NOW.toString());
    }

    @Test
    void binds390MinutesToTheExplicitWakeDay() {
        when(sleep.create(eq(OWNER), any())).thenReturn(saved(390));
        assertThat(call("recordSleepDuration", """
                {"sleepDate":"2026-11-20","durationMinutes":390,"feeling":"TIRED"}
                """)).contains("\"durationMinutes\":390", "\"feeling\":\"TIRED\"");

        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq(OWNER), write.capture());
        assertThat(write.getValue().method()).isEqualTo("DURATION");
        assertThat(write.getValue().endDay()).isEqualTo("2026-11-20");
        assertThat(write.getValue().minutes().intValue()).isEqualTo(390);
        assertThat(write.getValue().start()).isNull();
        assertThat(write.getValue().end()).isNull();
    }

    @Test
    void preservesCivilEndpointsForCrossMidnightSleep() {
        when(sleep.create(eq(OWNER), any())).thenReturn(saved(390));
        assertThat(call("recordSleepRange", """
                {"start":"2026-11-19T23:41","end":"2026-11-20T06:11","feeling":"TIRED"}
                """)).contains("\"sleepDate\":\"2026-11-20\"", "\"durationMinutes\":390");

        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq(OWNER), write.capture());
        assertThat(write.getValue()).isEqualTo(new SessionWrite("MAIN", "RANGE", "TIRED", "Asia/Ho_Chi_Minh",
                new SessionWrite.CivilTime("2026-11-19", "23", "41", null),
                new SessionWrite.CivilTime("2026-11-20", "06", "11", null), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"amountMl\":250.5}", "{\"amountMl\":\"250\"}", "{\"amountMl\":250,\"owner\":\"another\"}",
        "{\"amountMl\":250,\"loggedAt\":null}", "{\"amountMl\":250,\"amountMl\":300}", "{\"amountMl\":0}",
        "{\"amountMl\":2001}", "{\"amountMl\":250,\"loggedAt\":\"2026-11-20T10:01:00+07:00\"}",
        "{\"amountMl\":250,\"loggedAt\":\"2026-02-30T10:00:00+07:00\"}", "{\"amountMl\":250}{}"
    })
    void refusesMalformedWaterBeforeIntentReviewOrCreate(String input) {
        assertThat(call("recordWater", input)).contains("INVALID_ARGUMENTS");
        verify(execution).begin();
        verifyNoInteractions(policy, water, sleep);
    }

    @Test
    void refusesAmbiguousSemanticIntentEvenWithWellTypedArguments() {
        when(policy.permitsWrite(any(), any())).thenReturn(false);
        assertThat(call("recordSleepDuration", """
                {"sleepDate":"2026-11-20","durationMinutes":390,"feeling":"TIRED"}
                """)).contains("UNCLEAR_INTENT");
        verifyNoInteractions(water, sleep);
    }

    @Test
    void refusesMissingFeeling() {
        assertThat(call("recordSleepDuration", "{\"sleepDate\":\"2026-11-20\",\"durationMinutes\":390}"))
                .contains("INVALID_ARGUMENTS", "\"field\":\"feeling\"");
        verifyNoInteractions(policy, sleep, water);
    }

    private String call(String name, String arguments) {
        return tools.callbacks().stream().filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow().call(arguments);
    }

    private static SessionResponse saved(int minutes) {
        return new SessionResponse("private-id", NOW, "MAIN", null, "TIRED", "2026-11-20", minutes,
                null, null, null, null, null, true);
    }
}
