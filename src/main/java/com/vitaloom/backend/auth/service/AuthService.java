package com.vitaloom.backend.auth.service;

import com.vitaloom.backend.auth.dto.response.AuthResponse;
import com.vitaloom.backend.auth.dto.response.UserResponse;

public interface AuthService {
    RegistrationResult register(String email, String password);
    AuthResponse login(String email, String password);
    RefreshResult refresh(String refreshToken);
    UserResponse currentUser(String userId);

    record RegistrationResult(UserResponse user, JwtTokenService.TokenPair tokens, boolean loginRequired) {
        public AuthResponse authResponse() {
            return tokens == null ? null : new AuthResponse(user, tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresInSeconds());
        }
    }

    record RefreshResult(String accessToken) {}
}
