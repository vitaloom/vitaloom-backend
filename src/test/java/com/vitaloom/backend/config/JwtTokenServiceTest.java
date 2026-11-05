package com.vitaloom.backend.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.vitaloom.backend.auth.service.JwtTokenService;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.beans.factory.InitializingBean;

class JwtTokenServiceTest {
    private static final String ACCESS_SECRET = "access-signing-secret-value-32-bytes!";
    private static final String REFRESH_SECRET = "refresh-signing-secret-value-32-bytes";
    private static final String ISSUER = "https://api.vitaloom.test";

    private JwtTokenService service() {
        AuthTokenConfig config = new AuthTokenConfig();
        JwtEncoder accessEncoder = config.accessJwtEncoder(ACCESS_SECRET);
        JwtEncoder refreshEncoder = config.refreshJwtEncoder(REFRESH_SECRET);
        JwtDecoder accessDecoder = config.accessJwtDecoder(ACCESS_SECRET, ISSUER);
        JwtDecoder refreshDecoder = config.refreshJwtDecoder(REFRESH_SECRET, ISSUER);
        return new JwtTokenService(accessEncoder, refreshEncoder, accessDecoder, refreshDecoder, ISSUER);
    }

    @Test
    void accessAndRefreshTokensHaveSeparateTypesAndKeys() {
        JwtTokenService tokens = service();
        var pair = tokens.issue("user-1", Instant.now());
        assertEquals("user-1", tokens.decodeAccess(pair.accessToken()).getSubject());
        assertEquals("user-1", tokens.validateRefresh(pair.refreshToken()));
        assertThrows(JwtException.class, () -> tokens.decodeAccess(pair.refreshToken()));
        assertThrows(JwtException.class, () -> tokens.validateRefresh(pair.accessToken()));
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        JwtTokenService tokens = service();
        var pair = tokens.issue("user-1", Instant.now().minusSeconds(31L * 24 * 60 * 60));
        assertThrows(JwtException.class, () -> tokens.validateRefresh(pair.refreshToken()));
    }

    @Test
    void expiredAccessMalformedTokenAndWrongIssuerAreRejected() {
        JwtTokenService tokens = service();
        var expired = tokens.issue("user-1", Instant.now().minusSeconds(1000));
        assertThrows(JwtException.class, () -> tokens.decodeAccess(expired.accessToken()));
        assertThrows(JwtException.class, () -> tokens.decodeAccess("not-a-jwt"));

        AuthTokenConfig config = new AuthTokenConfig();
        JwtTokenService otherIssuer = new JwtTokenService(
                config.accessJwtEncoder(ACCESS_SECRET), config.refreshJwtEncoder(REFRESH_SECRET),
                config.accessJwtDecoder(ACCESS_SECRET, "https://other.example"),
                config.refreshJwtDecoder(REFRESH_SECRET, "https://other.example"),
                "https://other.example");
        var foreign = otherIssuer.issue("user-1", Instant.now());
        assertThrows(JwtException.class, () -> tokens.decodeAccess(foreign.accessToken()));
        assertThrows(JwtException.class, () -> tokens.validateRefresh(foreign.refreshToken()));
    }

    @Test
    void signingSecretsMustBeDistinctAndAtLeast256Bits() throws Exception {
        AuthTokenConfig config = new AuthTokenConfig();
        config.validateJwtSecrets(ACCESS_SECRET, REFRESH_SECRET).afterPropertiesSet();
        InitializingBean sameSecret = config.validateJwtSecrets(ACCESS_SECRET, ACCESS_SECRET);
        assertThrows(IllegalStateException.class, sameSecret::afterPropertiesSet);
        InitializingBean shortSecret = config.validateJwtSecrets("short", REFRESH_SECRET);
        assertThrows(IllegalStateException.class, shortSecret::afterPropertiesSet);
    }
}
