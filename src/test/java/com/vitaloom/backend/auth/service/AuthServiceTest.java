package com.vitaloom.backend.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.vitaloom.backend.auth.dto.request.RegisterRequest;
import com.vitaloom.backend.auth.exception.EmailAlreadyRegisteredException;
import com.vitaloom.backend.auth.exception.InvalidCredentialsException;
import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.auth.repository.UserRepository;
import com.vitaloom.backend.config.AuthTokenConfig;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class AuthServiceTest {
    private static final String ACCESS_SECRET = "access-signing-secret-value-32-bytes!";
    private static final String REFRESH_SECRET = "refresh-signing-secret-value-32-bytes";
    private final Map<String, User> usersByEmail = new HashMap<>();
    private final Map<String, User> usersById = new HashMap<>();
    private final UserRepository users = (UserRepository) Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "findByEmailNormalized" -> Optional.ofNullable(usersByEmail.get((String) args[0]));
                    case "findById" -> Optional.ofNullable(usersById.get((String) args[0]));
                    case "save" -> {
                        User user = (User) args[0]; user.setId(UUID.randomUUID().toString());
                        usersByEmail.put(user.getEmailNormalized(), user); usersById.put(user.getId(), user); yield user;
                    }
                    case "toString" -> "InMemoryUserRepository";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtTokenService tokens = createTokens();
    private final AuthService service = new AuthService(users, encoder, tokens);

    private static JwtTokenService createTokens() {
        AuthTokenConfig config = new AuthTokenConfig();
        JwtEncoder accessEncoder = config.accessJwtEncoder(ACCESS_SECRET);
        JwtEncoder refreshEncoder = config.refreshJwtEncoder(REFRESH_SECRET);
        JwtDecoder accessDecoder = config.accessJwtDecoder(ACCESS_SECRET, "https://api.vitaloom.test");
        JwtDecoder refreshDecoder = config.refreshJwtDecoder(REFRESH_SECRET, "https://api.vitaloom.test");
        return new JwtTokenService(accessEncoder, refreshEncoder, accessDecoder, refreshDecoder, "https://api.vitaloom.test");
    }

    @Test
    void registerNormalizesEmailButPreservesPlusAndPasswordWhitespace() {
        var response = service.register(" User+tag@Example.COM ", "  secret  ");
        assertEquals("user+tag@example.com", response.user().email());
        assertNotNull(response.tokens().accessToken());
        assertNotNull(response.tokens().refreshToken());
        User saved = usersByEmail.get("user+tag@example.com");
        assertNotNull(saved);
        assertEquals("user+tag@example.com", saved.getEmailNormalized());
        assertNotEquals("  secret  ", saved.getPasswordHash());
        assertTrue(encoder.matches("  secret  ", saved.getPasswordHash()));
    }

    @Test
    void duplicateCanonicalEmailIsRejectedAndPlusTagsRemainDistinct() {
        service.register(" User+tag@Example.COM ", "password123");
        assertThrows(EmailAlreadyRegisteredException.class,
                () -> service.register("user+tag@example.com", "password123"));
        var separate = service.register("user@example.com", "password123");
        assertEquals("user@example.com", separate.user().email());
    }

    @Test
    void loginNormalizesEmailAndDoesNotTrimPassword() {
        var registered = service.register("user@example.com", "  password123  ");
        var login = service.login(" USER@example.com ", "  password123  ");
        assertEquals(registered.user(), login.user());
        assertThrows(InvalidCredentialsException.class,
                () -> service.login("user@example.com", "password123"));
    }

    @Test
    void registrationRequestStripsEmailBeforeEmailValidationAndAllowsLongPasswords() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var request = new RegisterRequest(" User+tag@Example.COM ", "a".repeat(1025));
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void registrationReportsLoginRequiredIfTokensFailAfterAccountCreation() {
        JwtTokenService failingTokens = new JwtTokenService(
                new AuthTokenConfig().accessJwtEncoder(ACCESS_SECRET),
                new AuthTokenConfig().refreshJwtEncoder(REFRESH_SECRET),
                new AuthTokenConfig().accessJwtDecoder(ACCESS_SECRET, "https://api.vitaloom.test"),
                new AuthTokenConfig().refreshJwtDecoder(REFRESH_SECRET, "https://api.vitaloom.test"),
                "https://api.vitaloom.test") {
            @Override public TokenPair issue(String userId, Instant now) {
                throw new TokenIssuanceException(new IllegalStateException("signing unavailable"));
            }
        };
        var fallback = new AuthService(users, encoder, failingTokens)
                .register("recover@example.com", "password123");
        assertTrue(fallback.loginRequired());
        assertNotNull(usersByEmail.get("recover@example.com"));
        assertThrows(EmailAlreadyRegisteredException.class,
                () -> service.register("recover@example.com", "password123"));
    }
}
