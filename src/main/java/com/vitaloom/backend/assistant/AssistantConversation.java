package com.vitaloom.backend.assistant;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assistant_course_conversations")
record AssistantConversation(@Id String id, String owner, @Version Long revision,
        Instant updatedAt, Instant expiresAt, boolean personalAllowed, List<Turn> turns) {

    AssistantConversation change(Instant now, boolean allowed, List<Turn> history) {
        return new AssistantConversation(id, owner, revision, now, expiresAt, allowed, history);
    }

    record Turn(String turnId, String text, Instant at, AssistantCourseAnswers.Answer reply) {}
}
