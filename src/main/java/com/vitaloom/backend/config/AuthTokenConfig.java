package com.vitaloom.backend.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class AuthTokenConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean InitializingBean validateJwtSecrets(@Value("${vitaloom.auth.access-secret}") String accessSecret,
                                               @Value("${vitaloom.auth.refresh-secret}") String refreshSecret) {
        return () -> {
            byte[] access = accessSecret.getBytes(StandardCharsets.UTF_8);
            byte[] refresh = refreshSecret.getBytes(StandardCharsets.UTF_8);
            if (access.length < 32 || refresh.length < 32 || MessageDigest.isEqual(access, refresh)) {
                throw new IllegalStateException("JWT access and refresh secrets must be distinct values of at least 32 bytes");
            }
        };
    }

    @Bean("accessJwtEncoder") public JwtEncoder accessJwtEncoder(@Value("${vitaloom.auth.access-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }
    @Bean("refreshJwtEncoder") public JwtEncoder refreshJwtEncoder(@Value("${vitaloom.auth.refresh-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }
    @Bean("accessJwtDecoder") public JwtDecoder accessJwtDecoder(@Value("${vitaloom.auth.access-secret}") String secret,
                                                            @Value("${vitaloom.auth.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return token -> {
            var jwt = decoder.decode(token);
            if (!"access".equals(jwt.getClaimAsString("type"))) throw new BadJwtException("Invalid access token type");
            return jwt;
        };
    }
    @Bean("refreshJwtDecoder") public JwtDecoder refreshJwtDecoder(@Value("${vitaloom.auth.refresh-secret}") String secret,
                                                              @Value("${vitaloom.auth.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return token -> {
            var jwt = decoder.decode(token);
            if (!"refresh".equals(jwt.getClaimAsString("type"))) throw new BadJwtException("Invalid refresh token type");
            return jwt;
        };
    }
    private static SecretKey key(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT signing secrets must be at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
