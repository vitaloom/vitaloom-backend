package com.vitaloom.backend.auth.service.impl;

import java.time.Clock;
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
import com.vitaloom.backend.auth.service.AuthService;
import com.vitaloom.backend.auth.service.JwtTokenService;
import com.vitaloom.backend.auth.service.TokenIssuanceException;
import com.vitaloom.backend.auth.service.AuthService.RefreshResult;
import com.vitaloom.backend.auth.service.AuthService.RegistrationResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final Clock clock;

    public AuthServiceImpl(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
    }

    public RegistrationResult register(String email, String password) {
        String normalized = normalizeEmail(email);
        if (users.findByEmailNormalized(normalized).isPresent()) throw new EmailAlreadyRegisteredException();
        User saved;
        try {
            saved = users.save(new User(normalized, normalized, passwordEncoder.encode(password)));
        } catch (DuplicateKeyException ex) {
            throw new EmailAlreadyRegisteredException();
        }
        UserResponse user = toResponse(saved);
        try {
            return new RegistrationResult(user, tokens.issue(saved.getId(), clock.instant()), false);
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
        return response(toResponse(user), tokens.issue(user.getId(), clock.instant()));
    }

    public RefreshResult refresh(String refreshToken) {
        String subject;
        try {
            subject = tokens.validateRefresh(refreshToken);
        } catch (RuntimeException ex) {
            throw new InvalidRefreshTokenException();
        }
        User user = users.findById(subject).orElseThrow(InvalidRefreshTokenException::new);
        return new RefreshResult(tokens.issueAccess(user.getId(), clock.instant()));
    }

    public UserResponse currentUser(String userId) {
        return users.findById(userId).map(AuthServiceImpl::toResponse).orElseThrow(UserNotFoundException::new);
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static UserResponse toResponse(User user) { return new UserResponse(user.getId(), user.getEmail()); }
    private static AuthResponse response(UserResponse user, JwtTokenService.TokenPair pair) {
        return new AuthResponse(user, pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds());
    }

}
