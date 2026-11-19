package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(AssistantActivation.Course.class)
final class AssistantCourseAnswers {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SleepJournalService journal;
    private final SleepRecentStatisticsService sleep;
    private final WaterService water;
    private final Clock clock;
    private final SleepInsightsStatisticsService insights;

    AssistantCourseAnswers(SleepJournalService journal, SleepRecentStatisticsService sleep,
            WaterService water, Clock clock) {
        this(journal, sleep, water, clock, null);
    }

    @Autowired
    AssistantCourseAnswers(SleepJournalService journal, SleepRecentStatisticsService sleep,
            WaterService water, Clock clock, SleepInsightsStatisticsService insights) {
        this.journal = journal;
        this.sleep = sleep;
        this.water = water;
        this.clock = clock;
        this.insights = insights;
    }

    AssistantConversationPersonalTools conversationTools(String owner, AssistantConversationTools evidence,
            boolean permitted, BooleanSupplier current) {
        return new AssistantConversationPersonalTools(owner, new AssistantPersonalDataService(sleep, water, clock),
                journal, insights, evidence, permitted, current);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    enum Intent { SLEEP_DAY, SLEEP_RECENT, WATER_DAY, WATER_WEEK }
    enum Window { TODAY, RECENT, WEEK_TO_DATE }
    enum Analysis { SUMMARY, MEANING, ANOMALY }

    record Context(Intent intent, Window window, Analysis analysis, LocalDate date) {
        @org.springframework.data.annotation.PersistenceCreator
        Context {
            if (window == null) window = intent == Intent.SLEEP_RECENT ? Window.RECENT : Window.TODAY;
            analysis = analysis == null ? Analysis.SUMMARY : analysis;
        }
    }

    record Source(String id, int version, String title, String route, String from, String to) {}
    record Part(String status, String text, Source source) {}
    record Answer(String outcome, List<Part> parts, Context context) {}
}
