package com.flashsale.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id private UUID id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "list_price", nullable = false)
    private BigDecimal listPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected Product() {}

    public static Product create(String sku, String name, BigDecimal listPrice, Instant now) {
        Product product = new Product();
        product.id = UUID.randomUUID();
        product.sku = sku;
        product.name = name;
        product.listPrice = listPrice;
        product.status = ProductStatus.ACTIVE;
        product.createdAt = now;
        product.updatedAt = now;
        return product;
    }

    public void update(String name, BigDecimal listPrice, ProductStatus status, Instant now) {
        this.name = name;
        this.listPrice = listPrice;
        this.status = status;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public BigDecimal listPrice() {
        return listPrice;
    }

    public ProductStatus status() {
        return status;
    }
}
