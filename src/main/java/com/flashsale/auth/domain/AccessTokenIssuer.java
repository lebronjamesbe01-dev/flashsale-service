package com.flashsale.auth.domain;

import java.time.Instant;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user, Instant now);

    record IssuedAccessToken(String value, String tokenId, Instant expiresAt) {}
}
