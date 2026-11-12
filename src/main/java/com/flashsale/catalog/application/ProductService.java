package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import com.flashsale.catalog.domain.ProductRepository;
import com.flashsale.catalog.domain.ProductStatus;
import com.flashsale.inventory.application.InventoryService;
import com.flashsale.outbox.application.Events;
import com.flashsale.outbox.application.OutboxWriter;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final InventoryService inventory;
    private final BusinessClock clock;
    private final OutboxWriter outbox;

    public ProductService(
            ProductRepository products,
            InventoryService inventory,
            BusinessClock clock,
            OutboxWriter outbox) {
        this.products = products;
        this.inventory = inventory;
        this.clock = clock;
        this.outbox = outbox;
    }

    /** Creates the product and its warehouse stock row in one transaction. */
    @Transactional
    public ProductView create(String sku, String name, BigDecimal listPrice, int initialStock) {
        if (products.existsBySku(sku)) {
            throw new BusinessException(ErrorCode.SKU_ALREADY_EXISTS);
        }
        Product product;
        try {
            product = products.saveAndFlush(Product.create(sku, name, listPrice, clock.now()));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.SKU_ALREADY_EXISTS);
        }
        inventory.initialize(product.id(), initialStock);
        return ProductView.of(product);
    }

    @Transactional
    public ProductView update(UUID id, String name, BigDecimal listPrice, ProductStatus status) {
        Product product = load(id);
        boolean deactivating = product.isActive() && status == ProductStatus.INACTIVE;
        product.update(name, listPrice, status, clock.now());
        if (deactivating) {
            // Upcoming flash sales of this product are withdrawn asynchronously by the flash sale
            // module, which also returns their reserved stock to the warehouse.
            outbox.write(
                    "product",
                    id,
                    Events.PRODUCT_DEACTIVATED,
                    new ProductDeactivated(id, product.sku(), clock.now()));
        }
        return ProductView.of(product);
    }

    /**
     * Reads the product and holds a share lock on it until the caller's transaction ends, so it
     * cannot be deactivated half-way through being added to a flash sale.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductView getLocked(UUID id) {
        return products.findForShareById(id)
                .map(ProductView::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
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
        return products.findAllById(ids).stream()
                .map(ProductView::of)
                .collect(Collectors.toMap(ProductView::id, Function.identity()));
    }

    private Product load(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
