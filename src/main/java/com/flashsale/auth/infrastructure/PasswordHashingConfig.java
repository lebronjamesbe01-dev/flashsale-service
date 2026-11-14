package com.flashsale.auth.infrastructure;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing: Argon2id with the OWASP baseline (19 MiB memory, 2 iterations, 1 lane).
 *
 * <p>New hashes are stored as {@code {argon2}...}. Hashes without a prefix are older BCrypt ones
 * (for example the demo accounts); they still verify, and login rewrites them as Argon2id.
 *
 * <p>Each Argon2 run holds 19 MiB of heap. With virtual threads a login burst could start thousands
 * at once and exhaust memory, so at most one run per CPU core happens at a time.
 */
@Configuration(proxyBeanMethods = false)
class PasswordHashingConfig {

    private static final String ARGON2 = "argon2";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;

    @Bean
    PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder legacyBcrypt = new BCryptPasswordEncoder(12);
        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder(
                        ARGON2,
                        Map.of(
                                ARGON2,
                                new Argon2PasswordEncoder(
                                        SALT_BYTES,
                                        HASH_BYTES,
                                        PARALLELISM,
                                        MEMORY_KIB,
                                        ITERATIONS),
                                "bcrypt",
                                legacyBcrypt));
        delegating.setDefaultPasswordEncoderForMatches(legacyBcrypt);
        int permits = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new ConcurrencyLimitedPasswordEncoder(delegating, permits);
    }
}
