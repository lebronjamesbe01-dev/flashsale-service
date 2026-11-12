package com.flashsale.catalog.application;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code product.deactivated} event. */
public record ProductDeactivated(UUID productId, String sku, Instant deactivatedAt) {}
