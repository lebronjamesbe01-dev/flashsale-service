package com.flashsale.shared.logging;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * A warning written at most once per interval, without a stack trace. Used on fail-open paths
 * (Redis unavailable) that would otherwise log one line per request during an outage.
 */
public final class ThrottledWarning {

    private final Logger log;
    private final long intervalMillis;
    private final AtomicLong lastMillis = new AtomicLong();

    public ThrottledWarning(Logger log, Duration interval) {
        this.log = log;
        this.intervalMillis = interval.toMillis();
    }

    public void warn(String format, Object... args) {
        long now = System.currentTimeMillis();
        long last = lastMillis.get();
        if (now - last >= intervalMillis && lastMillis.compareAndSet(last, now)) {
            log.warn(format, args);
        }
    }
}
