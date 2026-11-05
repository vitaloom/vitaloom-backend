package com.vitaloom.backend.auth.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.vitaloom.backend.auth.dto.response.AuthResponse;
import com.vitaloom.backend.auth.dto.response.UserResponse;
import com.vitaloom.backend.auth.exception.EmailAlreadyRegisteredException;
import com.vitaloom.backend.auth.exception.InvalidCredentialsException;
import com.vitaloom.backend.auth.exception.InvalidRefreshTokenException;
import com.vitaloom.backend.auth.exception.UserNotFoundException;
import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.auth.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    public RegistrationResult register(String email, String password) {
        String normalized = normalizeEmail(email);
        if (users.findByEmailNormalized(normalized).isPresent()) throw new EmailAlreadyRegisteredException();
        User saved;
        try {
            saved = users.save(new User(normalized, normalized, passwordEncoder.encode(password), Instant.now()));
        } catch (DuplicateKeyException ex) {
            throw new EmailAlreadyRegisteredException();
        }
        UserResponse user = toResponse(saved);
        try {
            return new RegistrationResult(user, tokens.issue(saved.getId(), Instant.now()), false);
        } catch (TokenIssuanceException ex) {
            return new RegistrationResult(user, null, true);
        }
    }

    public AuthResponse login(String email, String password) {
        Optional<User> candidate = users.findByEmailNormalized(normalizeEmail(email));
        if (candidate.isEmpty() || !passwordEncoder.matches(password, candidate.get().getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        User user = candidate.get();
        return response(toResponse(user), tokens.issue(user.getId(), Instant.now()));
    }

    public RefreshResult refresh(String refreshToken) {
        String subject;
        try {
            subject = tokens.validateRefresh(refreshToken);
        } catch (RuntimeException ex) {
            throw new InvalidRefreshTokenException();
        }
        User user = users.findById(subject).orElseThrow(InvalidRefreshTokenException::new);
        return new RefreshResult(tokens.issueAccess(user.getId(), Instant.now()));
    }

    public UserResponse currentUser(String userId) {
        return users.findById(userId).map(AuthService::toResponse).orElseThrow(UserNotFoundException::new);
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static UserResponse toResponse(User user) { return new UserResponse(user.getId(), user.getEmail()); }
    private static AuthResponse response(UserResponse user, JwtTokenService.TokenPair pair) {
        return new AuthResponse(user, pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds());
    }

    public record RegistrationResult(UserResponse user, JwtTokenService.TokenPair tokens, boolean loginRequired) {
        public AuthResponse authResponse() { return tokens == null ? null : response(user, tokens); }
    }
    public record RefreshResult(String accessToken) {}
}
