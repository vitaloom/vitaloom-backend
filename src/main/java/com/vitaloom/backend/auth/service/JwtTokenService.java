package com.vitaloom.backend.auth.service;

import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

public interface JwtTokenService {
    long ACCESS_SECONDS = 900;
    long REFRESH_SECONDS = 30L * 24 * 60 * 60;

    TokenPair issue(String userId, Instant now);
    String issueAccess(String userId, Instant now);
    String validateRefresh(String token);
    Jwt decodeAccess(String token);

    record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}
}
