package com.flashsale.shared.time;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Business time zone. "One flash sale purchase per day" is counted in this zone, not in the
 * server's or the database's zone.
 */
@Validated
@ConfigurationProperties("app.time")
public record TimeProperties(@NotNull ZoneId zone) {}
