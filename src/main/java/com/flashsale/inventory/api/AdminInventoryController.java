package com.flashsale.inventory.api;

import com.flashsale.inventory.application.InventoryService;
import com.flashsale.inventory.application.InventoryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Inventory")
@RestController
@RequestMapping("/api/v1/admin/inventory")
class AdminInventoryController {

    private final InventoryService inventory;

    AdminInventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(
            summary = "Warehouse stock of a product",
            description =
                    "onHand = in the warehouse, reserved = held for flash sale quotas,"
                            + " available = what new quotas can still use.")
    @GetMapping("/{productId}")
    InventoryView get(@PathVariable UUID productId) {
        return inventory.view(productId);
    }

    @Operation(
            summary = "Receive or remove stock",
            description =
                    "Positive delta = goods received, negative = goods removed. Rejected if stock"
                            + " would fall below what is reserved for flash sales.")
    @PostMapping("/{productId}/adjust")
    InventoryView adjust(@PathVariable UUID productId, @Valid @RequestBody AdjustRequest body) {
        return inventory.adjust(productId, body.delta());
    }

    record AdjustRequest(@Min(-10_000_000) @Max(10_000_000) int delta) {}
}
