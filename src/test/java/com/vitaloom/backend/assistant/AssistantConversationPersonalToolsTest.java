package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.dto.response.DailyWaterSummaryResponse;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final SleepRecentStatisticsService sleep = mock(SleepRecentStatisticsService.class);
    private final SleepJournalService journal = mock(SleepJournalService.class);
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final AssistantConversationTools evidence = new AssistantConversationTools(null, UUID.randomUUID());
    private final AssistantConversationPersonalTools tools = new AssistantConversationPersonalTools(OWNER,
            new AssistantPersonalDataService(sleep, water, Clock.fixed(NOW, ZoneOffset.UTC)),
            journal, null, evidence, true, current::get);

    @Test
    void deniedPermissionDoesNotReadPersonalServicesAndMarksTheTurn() {
        var journal = mock(SleepJournalService.class);
        var sleep = mock(SleepRecentStatisticsService.class);
        var deniedEvidence = new AssistantConversationTools(null, UUID.randomUUID());
        var denied = new AssistantConversationPersonalTools(OWNER,
                new AssistantPersonalDataService(sleep, water, Clock.fixed(NOW, ZoneOffset.UTC)), journal,
                null, deniedEvidence, false, current::get);

        assertThat(denied.readSleepPeriod(AssistantConversationPersonalTools.SleepWindow.THIS_WEEK).status())
                .isEqualTo("PERMISSION_REQUIRED");
        assertThat(denied.readWaterDay(DATE, true).status()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(deniedEvidence.personalDenied()).isTrue();
        verifyNoPersonalServiceCalls(journal, sleep);
        verify(water, never()).dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh");
    }

    @Test
    void missingSleepWindowAndInvalidWaterDatesReturnInvalidArgumentsWithoutEvidence() {
        assertThat(tools.readSleepPeriod(null).status()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.readWaterDay(null, true).status()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.readWaterDay("2026-11-19", true).status()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.readWaterWeek("2026-11-17").status()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(evidence.sources()).isEmpty();
        verify(water, never()).dailySummary(OWNER, DATE, "Asia/Ho_Chi_Minh");
        verify(water, never()).weeklyProgress(OWNER, "2026-11-17", "Asia/Ho_Chi_Minh");
    }

    @Test
    void absentTimingServiceIsReportedUnavailableWithoutPublishingEvidence() {
        assertThat(tools.readRecentSleepTiming().status()).isEqualTo("UNAVAILABLE");
        assertThat(evidence.sources()).isEmpty();
    }

    @Test
    void sleepPeriodPublishesTheSelectedWindowAndVerifiedCoverage() {
        when(sleep.recent(OWNER)).thenReturn(new SleepRecentStatisticsService.Report(
                new SleepRecentStatisticsService.Period(LocalDate.parse("2026-11-12"),
                        LocalDate.parse("2026-11-18"), "Asia/Ho_Chi_Minh"),
                "VERIFIED", List.of(
                        new SleepRecentStatisticsService.Day(LocalDate.parse("2026-11-16"), "RECORDED", 420),
                        new SleepRecentStatisticsService.Day(LocalDate.parse("2026-11-17"), "NOT_RECORDED", null),
                        new SleepRecentStatisticsService.Day(LocalDate.parse("2026-11-18"), "RECORDED", 480)),
                null, 7, 450, List.of(), List.of()));

        var result = tools.readSleepPeriod(AssistantConversationPersonalTools.SleepWindow.THIS_WEEK);

        assertThat(result.status()).isEqualTo("VERIFIED");
        var facts = (AssistantPersonalDataService.SleepPeriod) result.evidence().facts();
        assertThat(facts.from()).isEqualTo("2026-11-16");
        assertThat(facts.to()).isEqualTo("2026-11-18");
        assertThat(facts.recordedDays()).isEqualTo(2);
        assertThat(facts.days()).extracting(AssistantPersonalDataService.SleepDay::status)
                .containsExactly("RECORDED", "NOT_RECORDED", "RECORDED");
        assertThat(evidence.sources()).hasSize(1);
    }

    @Test
    void sleepDayReturnsNoDataForAnEmptyJournalAndBindsQueryToOwnerAndDate() {
        when(journal.list(OWNER, DATE, DATE)).thenReturn(List.of());

        var result = tools.readSleepDay(DATE, true);

        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.evidence().facts().toString()).contains("date=2026-11-18").doesNotContain("minutes");
        verify(journal).list(OWNER, DATE, DATE);
    }

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

    private static void verifyNoPersonalServiceCalls(SleepJournalService journal,
            SleepRecentStatisticsService sleep) {
        verify(journal, never()).list(OWNER, DATE, DATE);
        verify(sleep, never()).recent(OWNER);
    }
}
