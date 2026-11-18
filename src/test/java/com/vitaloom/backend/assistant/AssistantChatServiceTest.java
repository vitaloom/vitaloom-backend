package com.vitaloom.backend.assistant;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantChatServiceTest {
    private static final String COLLECTION = "assistant_sandbox_turns";
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AssistantProvider provider = mock(AssistantProvider.class);
    private AssistantChatService service;

    @BeforeEach
    void setup() {
        when(mongo.indexOps(COLLECTION)).thenReturn(mock(IndexOperations.class));
        service = new AssistantChatService(mongo, provider);
    }

    @Test
    void excludesAnExpiredResultBeforeMongoTtlCleanup() {
        UUID turnId = UUID.randomUUID();
        String claimId = "synthetic:" + turnId;
        when(mongo.insert(any(AssistantTurn.class), eq(COLLECTION))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mongo.findById(claimId, AssistantTurn.class, COLLECTION))
            .thenReturn(new AssistantTurn(claimId, AssistantTurn.Status.ANSWERED, Instant.EPOCH, null));

        assertThat(service.answer("synthetic", turnId)).isEqualTo(AssistantTurn.Status.PENDING);
        verifyNoInteractions(provider);
    }

    @Test
    void reportsAnUncertainOutcomeIfCompletionCannotFindItsClaim() {
        when(provider.confirmsFixture()).thenReturn(true);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(AssistantTurn.class), eq(COLLECTION)))
            .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(service.answer("synthetic", UUID.randomUUID())).isEqualTo(AssistantTurn.Status.PENDING);
        verify(provider).confirmsFixture();
    }

    @Test
    void refusesToReplayAClaimForADifferentFixture() {
        UUID turnId = UUID.randomUUID();
        String claimId = "synthetic:" + turnId;
        when(mongo.insert(any(AssistantTurn.class), eq(COLLECTION))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mongo.findById(claimId, AssistantTurn.class, COLLECTION)).thenReturn(
                new AssistantTurn(claimId, AssistantTurn.Status.ANSWERED, Instant.now().plusSeconds(60), "journal"));

        assertThat(service.answer("synthetic", turnId, "water", () -> {
            throw new AssertionError("A different fixture must not dispatch under the same turn ID");
        })).isEqualTo(AssistantTurn.Status.PENDING);
    }
}
