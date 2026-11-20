package com.vitaloom.backend.water.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Document("water_logs")
@Getter
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "user_logged_at_idx", def = "{'user_id': 1, 'logged_at': -1}"),
    @CompoundIndex(name = WaterLog.USER_LOGGED_AT_UNIQUE_INDEX,
            def = "{'user_id': 1, 'logged_at': 1}", unique = true)
})
public class WaterLog extends BaseDocument {
    public static final String USER_LOGGED_AT_UNIQUE_INDEX = "user_logged_at_unique_idx";

    @Field("user_id") private String userId;
    @Field("amount_ml") private int amountMl;
    @Field("logged_at") private Instant loggedAt;

    public WaterLog(String userId, int amountMl, Instant loggedAt) {
        this.userId = userId;
        this.amountMl = amountMl;
        this.loggedAt = loggedAt;
    }
}
