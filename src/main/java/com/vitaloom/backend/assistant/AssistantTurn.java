package com.vitaloom.backend.assistant;

import java.time.Instant;
import org.springframework.data.annotation.Id;

record AssistantTurn(
        @Id String id,
        Status status,
        Instant expiresAt,
        String fixture) {
    enum Status { PENDING, ANSWERED, DEPENDENCY_UNAVAILABLE }
}
