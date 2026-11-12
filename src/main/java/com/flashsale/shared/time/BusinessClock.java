package com.flashsale.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The only source of "now" in business code. Backed by an injectable {@link Clock}, so tests can
 * freeze time before, inside or after a flash sale slot.
 */
@Component
public class BusinessClock {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessClock(Clock clock, TimeProperties properties) {
        this.clock = clock;
        this.zone = properties.zone();
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public LocalDate dateOf(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    /** The half-open instant range [from, to) covered by a business day. */
    public DayRange dayRange(LocalDate day) {
        return new DayRange(
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public ZoneId zone() {
        return zone;
    }

    public record DayRange(Instant from, Instant to) {}
}
