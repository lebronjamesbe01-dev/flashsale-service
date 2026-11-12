package com.flashsale.auth.application;

import com.flashsale.auth.domain.Identifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Generates 6-digit OTP codes and hashes them with HMAC-SHA256 keyed by a server secret. The hash
 * is bound to the identifier, so a code issued for one account is useless for another, and a Redis
 * dump does not reveal codes.
 */
@Component
public class OtpCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public OtpCodec(AuthProperties properties) {
        this.key =
                new SecretKeySpec(
                        properties.otpHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String generate() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    public String hash(Identifier identifier, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest =
                    mac.doFinal((identifier.value() + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison so response timing does not reveal how many characters matched. */
    public boolean matches(Identifier identifier, String code, String expectedHash) {
        byte[] actual = hash(identifier, code).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
