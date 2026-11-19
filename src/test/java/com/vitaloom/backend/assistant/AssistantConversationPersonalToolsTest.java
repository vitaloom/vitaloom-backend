package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AssistantConversationPersonalToolsTest {
    private static final String OWNER = "fixture-owner";
    private static final String DATE = "2026-11-18";
    private static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
    private final WaterService water = mock(WaterService.class);
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final AssistantConversationTools evidence = new AssistantConversationTools(null, UUID.randomUUID());
    private final AssistantConversationPersonalTools tools = new AssistantConversationPersonalTools(OWNER,
            new AssistantPersonalDataService(mock(SleepRecentStatisticsService.class), water,
                    Clock.fixed(NOW, ZoneOffset.UTC)),
            mock(SleepJournalService.class), null, evidence, true, current::get);

    @Test
    void revocationDuringReadDiscardsPrivateEvidence() {
        when(water.dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh")).thenAnswer(call -> {
            current.set(false);
            return summary();
        });

        assertThatThrownBy(() -> tools.readWaterDay(DATE, true))
                .isInstanceOf(AssistantPersonalDataTools.PersonalDataAccessDeniedException.class);
        assertThat(evidence.sources()).isEmpty();
    }

    @Test
    void journalDetailsAreRequestedExplicitlyAndExcludeInternalIdentifiers() {
        when(water.dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh")).thenReturn(summary());

        assertThat(tools.readWaterDay(DATE, null).evidence().facts().toString()).doesNotContain("entries");
        assertThat(tools.readWaterDay(DATE, true).evidence().facts().toString())
                .contains("amountMl=250", "recordedAt=").doesNotContain(OWNER, "internal-log-id");
    }

    private DailyWaterSummaryResponse summary() {
        return new DailyWaterSummaryResponse(DATE, 2000, 250, .125,
                List.of(new WaterLogResponse("internal-log-id", 250, NOW)));
    }
}
