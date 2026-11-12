package com.flashsale.auth.infrastructure;

import com.flashsale.auth.application.AuthProperties;
import com.flashsale.auth.domain.AccessTokenRevocation;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs and verifies access tokens with HS256. Verification checks signature, expiry, issuer and
 * the Redis deny list, so a logged-out token is rejected on every instance.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties, AccessTokenRevocation revocation) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(secretKey(properties))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(properties.issuer()),
                        jwt ->
                                jwt.getId() != null && !revocation.isRevoked(jwt.getId())
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                                new OAuth2Error(
                                                        "invalid_token",
                                                        "Token has been revoked",
                                                        null))));
        return decoder;
    }

    private static SecretKey secretKey(AuthProperties properties) {
        return new SecretKeySpec(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
