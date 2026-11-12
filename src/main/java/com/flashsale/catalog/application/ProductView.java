package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import java.math.BigDecimal;
import java.util.UUID;

/** Read model of a product shared with other modules (they never see the JPA entity). */
public record ProductView(
        UUID id, String sku, String name, BigDecimal listPrice, String status, boolean active) {

    static ProductView of(Product product) {
        return new ProductView(
                product.id(),
                product.sku(),
                product.name(),
                product.listPrice(),
                product.status().name(),
                product.isActive());
    }
}
