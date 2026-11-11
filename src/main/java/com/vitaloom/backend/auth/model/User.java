package com.vitaloom.backend.auth.model;

import com.vitaloom.backend.shared.persistence.AuditedDocument;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Document("users")
@Getter
@NoArgsConstructor
public class User extends AuditedDocument {
    @Field("email") private String email;
    @Field("email_normalized") @Indexed(name = "emailNormalized", unique = true) private String emailNormalized;
    @Field("password_hash") private String passwordHash;

    public User(String email, String emailNormalized, String passwordHash) {
        this.email = email;
        this.emailNormalized = emailNormalized;
        this.passwordHash = passwordHash;
    }
}
