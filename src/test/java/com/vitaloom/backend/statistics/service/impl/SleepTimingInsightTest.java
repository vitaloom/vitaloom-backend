package com.vitaloom.backend.statistics.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeWindow;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SleepTimingInsightTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Period PERIOD = new Period(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-09"), "Asia/Ho_Chi_Minh");

    @Test
    void requiresThreeSelectedZoneRangeDaysAndRetainsBedtimeRelativeDay() {
        var two = window(day("one", "2026-11-03", "2026-11-02T23:00:00Z", "2026-11-03T06:00:00Z"),
                day("two", "2026-11-04", "2026-11-04T00:00:00Z", "2026-11-04T07:00:00Z"));
        assertEquals("INSUFFICIENT_DATA", SleepTimingInsight.evaluate(PERIOD, two, UTC).status());

        var three = window(day("one", "2026-11-03", "2026-11-02T23:00:00Z", "2026-11-03T06:00:00Z"),
                day("two", "2026-11-04", "2026-11-04T00:00:00Z", "2026-11-04T07:00:00Z"),
                day("three", "2026-11-05", "2026-11-05T01:00:00Z", "2026-11-05T08:00:00Z"));
        var item = SleepTimingInsight.evaluate(PERIOD, three, UTC);

        assertEquals("READY", item.status());
        assertEquals(3, item.sample().get("eligibleRangeDays"));
        Map<?, ?> bedtime = (Map<?, ?>) item.evidence().get("bedtime");
        assertEquals(-60, bedtime.get("minMinute"));
        assertEquals(60, bedtime.get("maxMinute"));
        assertEquals(3, item.sourceRefs().size());
    }

    @Test
    void onlyOffersStoredZonesAndDoesNotMixTheirSamples() {
        var window = window(day("one", "2026-11-03", "2026-11-03T00:00:00Z", "2026-11-03T07:00:00Z"),
                new MainSleepRangeDay("other", LocalDate.parse("2026-11-04"), 420, "RANGE", "America/New_York",
                        Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-04T07:00:00Z")));

        assertEquals(List.of("America/New_York", "UTC"), SleepTimingInsight.availableTimeZones(window));
        assertEquals("INSUFFICIENT_DATA", SleepTimingInsight.evaluate(PERIOD, window, UTC).status());
        assertEquals("INSUFFICIENT_DATA", SleepTimingInsight.evaluate(PERIOD, window, null).status());
    }

    @Test
    void blocksWindowContainingDstTransition() {
        var period = new Period(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07"), "America/New_York");
        var window = window(newYorkDay("one", "2026-11-02"), newYorkDay("two", "2026-11-03"), newYorkDay("three", "2026-11-04"));
        assertEquals("INSUFFICIENT_DATA", SleepTimingInsight.evaluate(period, window, ZoneId.of("America/New_York")).status());
    }

    @Test
    void excludesOnlyTheRangeCrossingATransitionBeforeTheWindow() {
        var period = new Period(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"), "Asia/Ho_Chi_Minh");
        var crossing = new MainSleepRangeDay("crossing", LocalDate.parse("2026-11-02"), 1382, "RANGE",
                "America/New_York", Instant.parse("2026-11-01T05:59:00Z"), Instant.parse("2026-11-02T05:01:00Z"));
        var first = newYorkDay("first", "2026-11-03");
        var second = newYorkDay("second", "2026-11-04");
        var third = newYorkDay("third", "2026-11-05");
        var zone = ZoneId.of("America/New_York");

        assertEquals("INSUFFICIENT_DATA", SleepTimingInsight.evaluate(period, window(crossing, first, second), zone).status());
        var item = SleepTimingInsight.evaluate(period, window(crossing, first, second, third), zone);
        assertEquals("READY", item.status());
        assertEquals(List.of("first", "second", "third"), item.sourceRefs());
        assertEquals(3, item.sample().get("eligibleRangeDays"));
    }

    @Test
    void unverifiedReadDoesNotExposePartialTimingEvidence() {
        var window = new MainSleepRangeWindow(false, List.of(day("one", "2026-11-03", "2026-11-03T00:00:00Z", "2026-11-03T07:00:00Z")));
        var item = SleepTimingInsight.evaluate(PERIOD, window, UTC);
        assertEquals("UNVERIFIED_DATA", item.status());
        assertNull(item.evidence());
        assertNull(item.sourceRefs());
    }

    private static MainSleepRangeWindow window(MainSleepRangeDay... rows) {
        return new MainSleepRangeWindow(true, List.of(rows));
    }

    private static MainSleepRangeDay day(String id, String wakeDay, String start, String end) {
        return new MainSleepRangeDay(id, LocalDate.parse(wakeDay), 420, "RANGE", "UTC", Instant.parse(start), Instant.parse(end));
    }

    private static MainSleepRangeDay newYorkDay(String id, String wakeDay) {
        LocalDate day = LocalDate.parse(wakeDay);
        return new MainSleepRangeDay(id, day, 420, "RANGE", "America/New_York",
                day.atTime(0, 0).atZone(ZoneId.of("America/New_York")).toInstant(),
                day.atTime(7, 0).atZone(ZoneId.of("America/New_York")).toInstant());
    }
}
