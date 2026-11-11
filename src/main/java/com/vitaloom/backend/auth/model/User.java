package com.vitaloom.backend.auth.model;

import java.time.Instant;
import com.vitaloom.backend.shared.persistence.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public class User extends BaseDocument {
    @Field("email") private String email;
    @Field("email_normalized") @Indexed(name = "emailNormalized", unique = true) private String emailNormalized;
    @Field("password_hash") private String passwordHash;
    @Field("created_at") private Instant createdAt;

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
