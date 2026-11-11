package com.vitaloom.backend.auth.mapper;

import com.vitaloom.backend.auth.dto.response.AuthResponse;
import com.vitaloom.backend.auth.dto.response.UserResponse;
import com.vitaloom.backend.auth.model.User;
import com.vitaloom.backend.auth.service.JwtTokenService;

public final class AuthMapper {
    private AuthMapper() {}

    public static UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail());
    }

    public static AuthResponse toAuthResponse(UserResponse user, JwtTokenService.TokenPair tokens) {
        return new AuthResponse(user, tokens.accessToken(), tokens.refreshToken(), tokens.expiresInSeconds());
    }
}
