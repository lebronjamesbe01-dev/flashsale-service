package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import com.flashsale.catalog.domain.ProductRepository;
import com.flashsale.catalog.domain.ProductStatus;
import com.flashsale.inventory.application.InventoryService;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final InventoryService inventory;
    private final BusinessClock clock;

    public ProductService(
            ProductRepository products, InventoryService inventory, BusinessClock clock) {
        this.products = products;
        this.inventory = inventory;
        this.clock = clock;
    }

    /** Creates the product and its warehouse stock row in one transaction. */
    @Transactional
    public ProductView create(String sku, String name, BigDecimal listPrice, int initialStock) {
        if (products.existsBySku(sku)) {
            throw new BusinessException(ErrorCode.SKU_ALREADY_EXISTS);
        }
        Product product = products.save(Product.create(sku, name, listPrice, clock.now()));
        inventory.initialize(product.id(), initialStock);
        return ProductView.of(product);
    }

    @Transactional
    public ProductView update(UUID id, String name, BigDecimal listPrice, ProductStatus status) {
        Product product = load(id);
        product.update(name, listPrice, status, clock.now());
        return ProductView.of(product);
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) {
        return ProductView.of(load(id));
    }

    @Transactional(readOnly = true)
    public Page<ProductView> list(Pageable pageable) {
        return products.findAll(pageable).map(ProductView::of);
    }

    @Transactional(readOnly = true)
    public Map<UUID, ProductView> getAll(Collection<UUID> ids) {
        List<Product> found = products.findAllById(ids);
        return found.stream()
                .map(ProductView::of)
                .collect(Collectors.toMap(ProductView::id, Function.identity()));
    }

    private Product load(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
