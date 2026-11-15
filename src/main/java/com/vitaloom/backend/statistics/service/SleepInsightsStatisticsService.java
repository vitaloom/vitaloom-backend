package com.vitaloom.backend.statistics.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface SleepInsightsStatisticsService {
    Insights insights(String accountId);
    Detail detail(String accountId, String code);
    record Period(LocalDate start, LocalDate end, String timeZone) {}
    record Insights(Period period, List<Item> items) { public Insights { items = List.copyOf(items); } }
    record Item(String code, String category, String status, String title, boolean highlight, boolean locked,
                String summary, Map<String, Object> sample, Map<String, Object> evidence,
                List<String> sourceRefs, List<String> limitations, String policyVersion, String evidenceVersion) {}
    record Detail(String code, String category, String status, Period period, String title, String summary,
                  Map<String, Object> sample, Map<String, Object> evidence, List<String> sourceRefs,
                  List<String> limitations, String policyVersion, String evidenceVersion) {}
}
