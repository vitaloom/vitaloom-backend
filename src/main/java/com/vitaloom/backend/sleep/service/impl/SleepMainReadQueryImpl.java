package com.vitaloom.backend.sleep.service.impl;

import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.sleep.model.SleepSession;
import com.vitaloom.backend.sleep.repository.SleepSessionRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SleepMainReadQueryImpl implements SleepMainReadQuery {
    private final SleepSessionRepository sessions;

    public SleepMainReadQueryImpl(SleepSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public MainSleepWindow readWindow(String accountId, LocalDate fromInclusive, LocalDate toInclusive) {
        List<SleepSession> rows = sessions.findMainByOwnerAndDayBetween(
                accountId, fromInclusive.toString(), toInclusive.toString());
        Map<LocalDate, List<MainSleepDay>> byDay = new HashMap<>();
        Set<LocalDate> disputedDays = new HashSet<>();
        boolean verified = true;
        for (SleepSession row : rows) {
            LocalDate day;
            try {
                day = LocalDate.parse(row.getDay());
                if (!day.toString().equals(row.getDay()) || day.isBefore(fromInclusive) || day.isAfter(toInclusive)) {
                    verified = false;
                    continue;
                }
            } catch (DateTimeParseException | NullPointerException exception) {
                verified = false;
                continue;
            }
            if (row.getConfirmedAt() == null || row.getMinutes() < 1 || row.getMinutes() > 1440
                    || (!"RANGE".equals(row.getMethod()) && !"DURATION".equals(row.getMethod()))) {
                verified = false;
                disputedDays.add(day);
                continue;
            }
            byDay.computeIfAbsent(day, ignored -> new ArrayList<>())
                    .add(new MainSleepDay(row.getId(), day, row.getMinutes()));
        }

        List<MainSleepDay> records = new ArrayList<>();
        for (List<MainSleepDay> sameDay : byDay.values()) {
            if (sameDay.size() == 1 && !disputedDays.contains(sameDay.getFirst().day())) {
                records.add(sameDay.getFirst());
            } else {
                verified = false;
            }
        }
        records.sort((left, right) -> left.day().compareTo(right.day()));
        return new MainSleepWindow(verified, records);
    }
}
