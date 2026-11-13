package com.vitaloom.backend.sleep.controller;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sleep/v2/sessions")
@RequiredArgsConstructor
public class SleepJournalController {
    private final SleepJournalService journal;

    @ModelAttribute
    void disableCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    public ListResponse list(Authentication auth, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return new ListResponse(journal.list(auth.getName(), from, to));
    }

    @GetMapping("/{id}")
    public SessionResponse detail(Authentication auth, @PathVariable String id) {
        return journal.detail(auth.getName(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(Authentication auth, @RequestBody SessionWrite request) {
        return journal.create(auth.getName(), request);
    }

    @PutMapping("/{id}")
    public SessionResponse update(Authentication auth, @PathVariable String id, @RequestBody SessionWrite request) {
        return journal.update(auth.getName(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable String id) {
        journal.delete(auth.getName(), id);
    }

    public record ListResponse(List<SessionResponse> sessions) {}
}
