package com.vitaloom.backend.auth.repository;

import java.util.Optional;
import com.vitaloom.backend.auth.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmailNormalized(String emailNormalized);
}
