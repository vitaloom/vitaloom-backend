package com.vitaloom.backend.statistics.service;

import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public interface SleepInsightsStatisticsService {
    Insights insights(String accountId);
    Insights goalInsights(String accountId);
    Insights trendInsights(String accountId);
    Insights fullInsights(String accountId, ZoneId selectedZone);
    List<Highlight> recentHighlights(String accountId, Period period, MainSleepWindow a01Window);
    List<Highlight> fullHighlights(String accountId, Period period, MainSleepWindow currentDuration, ZoneId selectedZone);
    Detail detail(String accountId, String code);
    Detail detail(String accountId, String code, ZoneId selectedZone);
    record Period(LocalDate start, LocalDate end, String timeZone) {}
    record Insights(Period period, List<Item> items, List<String> timeZones) {
        public Insights(Period period, List<Item> items) { this(period, items, null); }
        public Insights {
            items = List.copyOf(items);
            timeZones = timeZones == null ? null : List.copyOf(timeZones);
        }
    }
    record Item(String code, String category, String status, String title, boolean highlight, boolean locked,
                String summary, Map<String, Object> sample, Map<String, Object> evidence,
                List<String> sourceRefs, List<String> limitations, String policyVersion, String evidenceVersion) {}
    record Highlight(String code, String status, String title, String summary) {}
    record Detail(String code, String category, String status, Period period, String title, String summary,
                  Map<String, Object> sample, Map<String, Object> evidence, List<String> sourceRefs,
                  List<String> limitations, String policyVersion, String evidenceVersion) {}
}
