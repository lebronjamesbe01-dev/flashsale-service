package com.flashsale.auth.infrastructure;

import com.flashsale.auth.application.AuthProperties;
import com.flashsale.auth.domain.AccessTokenIssuer;
import com.flashsale.auth.domain.User;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Access token claims: {@code iss, sub=userId, role, jti, iat, exp}. No personal data inside. */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    JwtAccessTokenIssuer(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    public IssuedAccessToken issue(User user, Instant now) {
        String tokenId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .subject(user.id().toString())
                        .id(tokenId)
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .claim("role", user.role().name())
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, tokenId, expiresAt);
    }
}
