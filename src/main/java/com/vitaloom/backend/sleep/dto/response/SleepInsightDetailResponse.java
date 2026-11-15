package com.vitaloom.backend.sleep.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SleepInsightDetailResponse(String code, String category, String status, Period period, String title,
        String summary, Map<String, Object> sample, Map<String, Object> evidence, List<String> sourceRefs,
        List<String> limitations, String policyVersion, String evidenceVersion) {
    public record Period(LocalDate start, LocalDate end, String timeZone) {}
}
