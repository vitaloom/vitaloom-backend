package com.vitaloom.backend.auth.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    public static final long ACCESS_SECONDS = 900;
    public static final long REFRESH_SECONDS = 30L * 24 * 60 * 60;
    private final JwtEncoder accessEncoder;
    private final JwtEncoder refreshEncoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;

    public JwtTokenService(@Qualifier("accessJwtEncoder") JwtEncoder accessEncoder,
                           @Qualifier("refreshJwtEncoder") JwtEncoder refreshEncoder,
                           @Qualifier("accessJwtDecoder") JwtDecoder accessDecoder,
                           @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
                           @Value("${vitaloom.auth.issuer}") String issuer) {
        this.accessEncoder = accessEncoder;
        this.refreshEncoder = refreshEncoder;
        this.accessDecoder = accessDecoder;
        this.refreshDecoder = refreshDecoder;
        this.issuer = issuer;
    }

    public TokenPair issue(String userId, Instant now) {
        try {
            String access = create(accessEncoder, userId, now, ACCESS_SECONDS, "access");
            String refresh = create(refreshEncoder, userId, now, REFRESH_SECONDS, "refresh");
            return new TokenPair(access, refresh, ACCESS_SECONDS);
        } catch (JwtException ex) {
            throw new TokenIssuanceException(ex);
        }
    }

    public String issueAccess(String userId, Instant now) {
        try { return create(accessEncoder, userId, now, ACCESS_SECONDS, "access"); }
        catch (JwtException ex) { throw new TokenIssuanceException(ex); }
    }

    public String validateRefresh(String token) {
        Jwt jwt = refreshDecoder.decode(token);
        if (!"refresh".equals(jwt.getClaimAsString("type")) || !issuer.equals(jwt.getClaimAsString("iss"))) {
            throw new JwtException("Invalid refresh token");
        }
        return jwt.getSubject();
    }

    public Jwt decodeAccess(String token) {
        Jwt jwt = accessDecoder.decode(token);
        if (!"access".equals(jwt.getClaimAsString("type")) || !issuer.equals(jwt.getClaimAsString("iss"))) {
            throw new JwtException("Invalid access token");
        }
        return jwt;
    }

    private String create(JwtEncoder encoder, String userId, Instant now, long seconds, String type) {
        Instant expires = now.plus(seconds, ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(userId).issuedAt(now)
                .expiresAt(expires).claim("type", type).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}
}
