package com.flashsale.auth.domain;

import java.time.Instant;

/**
 * Access tokens are stateless JWTs, so logout cannot delete them. Instead the token id is kept on a
 * deny list until the token would have expired anyway, and every request checks that list.
 */
public interface AccessTokenRevocation {

    void revoke(String tokenId, Instant expiresAt);

    boolean isRevoked(String tokenId);
}
