package com.vitaloom.backend.sleep.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SleepInsightsResponse(Period period, List<Item> items) {
    public SleepInsightsResponse { items = List.copyOf(items); }
    public record Period(LocalDate start, LocalDate end, String timeZone) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String code, String category, String status, String title, Boolean highlight, Boolean locked,
                       String summary, Map<String, Object> sample, Map<String, Object> evidence,
                       List<String> sourceRefs, List<String> limitations, String policyVersion, String evidenceVersion) {}
}
