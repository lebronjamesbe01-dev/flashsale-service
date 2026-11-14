package com.flashsale.auth.application;

import com.flashsale.auth.domain.AccessTokenIssuer;
import com.flashsale.auth.domain.AccessTokenIssuer.IssuedAccessToken;
import com.flashsale.auth.domain.AccessTokenRevocation;
import com.flashsale.auth.domain.Identifier;
import com.flashsale.auth.domain.RefreshToken;
import com.flashsale.auth.domain.RefreshTokenRepository;
import com.flashsale.auth.domain.User;
import com.flashsale.auth.domain.UserRepository;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, token refresh with rotation, logout. Access tokens are short-lived JWTs; refresh tokens
 * are opaque random strings stored hashed. A refresh token can be used once: replaying an old one
 * is treated as theft and revokes every token of that login.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AccessTokenRevocation revocation;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final BusinessClock clock;
    private final String dummyPasswordHash;

    public AuthenticationService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            AccessTokenIssuer accessTokenIssuer,
            AccessTokenRevocation revocation,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            BusinessClock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokenIssuer = accessTokenIssuer;
        this.revocation = revocation;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenPair login(Identifier identifier, String rawPassword) {
        Optional<User> found = users.findByIdentifier(identifier.value());
        // Always run one hash check so "no such user" costs the same time as "wrong password".
        String hash = found.map(User::passwordHash).orElse(dummyPasswordHash);
        boolean passwordOk = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !passwordOk || !found.get().isActive()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();
        // An old BCrypt hash is rewritten as Argon2id now that the plain password is known.
        if (passwordEncoder.upgradeEncoding(hash)) {
            users.rehashPassword(user.id(), hash, passwordEncoder.encode(rawPassword));
        }
        return issueTokens(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = clock.now();
        RefreshToken token =
                refreshTokens
                        .findByTokenHash(sha256(rawRefreshToken))
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeFamily(token.familyId(), now);
            log.warn("Refresh token reuse detected, revoked {} tokens of the family", revoked);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (token.isExpired(now) || refreshTokens.revokeIfActive(token.id(), now) == 0) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        User user =
                users.findById(token.userId())
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        return issueTokens(user, token.familyId());
    }

    @Transactional
    public void logout(
            UUID userId,
            String accessTokenId,
            Instant accessTokenExpiresAt,
            String rawRefreshToken) {
        revocation.revoke(accessTokenId, accessTokenExpiresAt);
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens
                    .findByTokenHash(sha256(rawRefreshToken))
                    .filter(t -> t.userId().equals(userId))
                    .ifPresent(t -> refreshTokens.revokeFamily(t.familyId(), clock.now()));
        }
    }

    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        return users.findById(userId)
                .map(UserProfile::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        Instant now = clock.now();
        IssuedAccessToken access = accessTokenIssuer.issue(user, now);
        String rawRefresh = newOpaqueToken();
        refreshTokens.save(
                RefreshToken.issue(
                        user.id(),
                        sha256(rawRefresh),
                        familyId,
                        now,
                        now.plus(properties.refreshTokenTtl())));
        return new TokenPair(access.value(), rawRefresh, properties.accessTokenTtl().toSeconds());
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
