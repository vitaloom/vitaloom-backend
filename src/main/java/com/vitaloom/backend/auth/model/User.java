package com.vitaloom.backend.auth.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public class User extends BaseDocument {
    private String email;
    @Indexed(unique = true) private String emailNormalized;
    private String passwordHash;
    private Instant createdAt;

    public User() {}
    public User(String email, String emailNormalized, String passwordHash, Instant createdAt) {
        this.email = email;
        this.emailNormalized = emailNormalized;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }
    public String getEmail() { return email; }
    public String getEmailNormalized() { return emailNormalized; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
}
