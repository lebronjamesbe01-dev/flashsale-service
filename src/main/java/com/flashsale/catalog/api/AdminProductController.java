package com.flashsale.catalog.api;

import com.flashsale.catalog.application.ProductService;
import com.flashsale.catalog.application.ProductView;
import com.flashsale.catalog.domain.ProductStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Products")
@RestController
@RequestMapping("/api/v1/admin/products")
class AdminProductController {

    private final ProductService products;

    AdminProductController(ProductService products) {
        this.products = products;
    }

    @Operation(
            summary = "Create a product with its initial warehouse stock",
            description = "Stock can be changed later with the inventory endpoints.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProductView create(@Valid @RequestBody CreateProductRequest body) {
        return products.create(body.sku(), body.name(), body.listPrice(), body.initialStock());
    }

    @Operation(summary = "Update name, list price or status")
    @PutMapping("/{id}")
    ProductView update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest body) {
        return products.update(id, body.name(), body.listPrice(), body.status());
    }

    @Operation(summary = "Get a product")
    @GetMapping("/{id}")
    ProductView get(@PathVariable UUID id) {
        return products.get(id);
    }

    @Operation(summary = "List products, newest first")
    @GetMapping
    PageResponse<ProductView> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<ProductView> result =
                products.list(PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return new PageResponse<>(result.getContent(), page, size, result.getTotalElements());
    }

    record CreateProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sku,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal listPrice,
            @Min(0) @Max(10_000_000) int initialStock) {}

    record UpdateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal listPrice,
            @NotNull ProductStatus status) {}

    record PageResponse<T>(List<T> items, int page, int size, long total) {}
}
