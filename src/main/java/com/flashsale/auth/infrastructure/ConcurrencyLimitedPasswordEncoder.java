package com.flashsale.auth.infrastructure;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lets only a fixed number of hash computations run at the same time; extra callers wait. Bounds
 * the memory a memory-hard hash (Argon2) can take during a login burst.
 */
final class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;

    ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent) {
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return limited(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return limited(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T limited(Supplier<T> work) {
        permits.acquireUninterruptibly();
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
