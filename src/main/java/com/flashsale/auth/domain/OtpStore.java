package com.flashsale.auth.domain;

import java.time.Duration;
import java.util.Optional;

/** Short-lived storage for one-time passwords. Implementations keep only a hash of the code. */
public interface OtpStore {

    void save(Identifier identifier, String codeHash, Duration ttl);

    /**
     * Counts one verification attempt and returns the stored hash, or empty when there is no code
     * or the attempt limit is exceeded (the code is then destroyed). Counting before comparing
     * means parallel guesses cannot bypass the limit.
     */
    Optional<String> registerAttempt(Identifier identifier, int maxAttempts);

    void delete(Identifier identifier);

    boolean exists(Identifier identifier);

    /** True when a new code may be sent now; starts the cooldown window if so. */
    boolean tryStartResendCooldown(Identifier identifier, Duration cooldown);
}
