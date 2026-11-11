package com.vitaloom.backend.water.repository;

import java.time.Instant;
import java.util.List;
import com.vitaloom.backend.water.model.WaterLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface WaterLogRepository extends MongoRepository<WaterLog, String> {
    @Query(value = "{'user_id': ?0, 'logged_at': {'$gte': ?1, '$lt': ?2}}", sort = "{'logged_at': -1}")
    List<WaterLog> findByUserIdAndLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(
            String userId, Instant startInclusive, Instant endExclusive);
}
