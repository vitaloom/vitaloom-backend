package com.vitaloom.backend.statistics.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.contract.SleepGoalReadQuery;
import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsight;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SleepComparisonInsightsTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final String ACCOUNT = "owner";
    private static final LocalDate START = LocalDate.parse("2026-11-08");
    private static final LocalDate END = START.plusDays(6);
    private final SleepMainReadQuery main = mock(SleepMainReadQuery.class);
    private final SleepInsightsStatisticsServiceImpl service = new SleepInsightsStatisticsServiceImpl(
            main, mock(SleepGoalReadQuery.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void a05UsesRecordedWeekdaysAndBothWeekendDaysIncludingToday() {
        var rows = List.of(day(0, 420), day(1, 301), day(2, 300), day(3, 301), day(6, 420));
        when(main.readWindow(ACCOUNT, START, END)).thenReturn(
                new MainSleepWindow(true, rows.subList(0, 4)),
                new MainSleepWindow(true, rows),
                new MainSleepWindow(false, rows));

        assertThat(service.detail(ACCOUNT, "A05").status()).isEqualTo("INSUFFICIENT_DATA");
        var detail = service.detail(ACCOUNT, "A05");
        assertThat(detail.status()).isEqualTo("READY");
        assertThat(detail.evidence()).containsEntry("weekday", Map.of("days", 3, "meanMinutes", 301))
                .containsEntry("weekend", Map.of("days", 2, "meanMinutes", 420))
                .containsEntry("deltaMinutes", -119);
        assertThat(service.detail(ACCOUNT, "A05").status()).isEqualTo("UNVERIFIED_DATA");
    }

    @Test
    void b02UsesOnlyThreeConfirmedDaysPerFeelingGroupAndShowsSignedDelta() {
        var rows = List.of(feeling(0, 410, "TIRED"), feeling(1, 385, "COMFORTABLE"),
                feeling(2, 340, "COMFORTABLE"), feeling(3, 400, "COMFORTABLE"),
                feeling(4, 395, "TIRED"), feeling(5, 600, null), feeling(6, 380, "TIRED"));
        when(main.readInsightWindow(ACCOUNT, START, END)).thenReturn(
                new MainSleepInsightWindow(true, rows.subList(0, 6)),
                new MainSleepInsightWindow(true, rows));

        assertThat(service.detail(ACCOUNT, "B02").status()).isEqualTo("INSUFFICIENT_DATA");
        var detail = service.detail(ACCOUNT, "B02");
        assertThat(detail.evidence()).containsEntry("comfortable", Map.of("days", 3, "meanMinutes", 375))
                .containsEntry("tired", Map.of("days", 3, "meanMinutes", 395))
                .containsEntry("deltaMinutes", -20);
        assertThat(detail.sourceRefs()).hasSize(6).doesNotContain("5");
        assertThat(service.detail(ACCOUNT, "B01").status()).isEqualTo("READY");
    }

    private static MainSleepDay day(int offset, int minutes) {
        return new MainSleepDay(Integer.toString(offset), START.plusDays(offset), minutes);
    }

    private static MainSleepInsight feeling(int offset, int minutes, String feeling) {
        return new MainSleepInsight(Integer.toString(offset), START.plusDays(offset), minutes, feeling, NOW);
    }
}
