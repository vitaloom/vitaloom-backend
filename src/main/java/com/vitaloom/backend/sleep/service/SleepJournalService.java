package com.vitaloom.backend.sleep.service;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import java.time.Instant;

public interface SleepJournalService {
    java.util.List<SessionResponse> list(String owner, String from, String to);
    SessionResponse detail(String owner, String id);
    SessionResponse create(String owner, SessionWrite request);
    SessionResponse update(String owner, String id, SessionWrite request, Instant expectedConfirmedAt);
    void delete(String owner, String id, Instant expectedConfirmedAt);
}
