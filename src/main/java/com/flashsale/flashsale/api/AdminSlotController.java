package com.flashsale.flashsale.api;

import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import com.flashsale.flashsale.application.SlotAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Flash sale slots")
@RestController
@RequestMapping("/api/v1/admin/flash-sale/slots")
class AdminSlotController {

    private final SlotAdminService admin;

    AdminSlotController(SlotAdminService admin) {
        this.admin = admin;
    }

    @Operation(
            summary = "Create a draft slot",
            description =
                    "Times are ISO-8601 instants, e.g. 2026-11-12T05:00:00Z (= 12:00 in Vietnam)."
                            + " A slot must stay within one day in Asia/Ho_Chi_Minh.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SlotView create(@Valid @RequestBody SlotRequest body) {
        return admin.create(body.name(), body.startAt(), body.endAt());
    }

    @Operation(summary = "Change name or time of a draft slot")
    @PutMapping("/{slotId}")
    SlotView reschedule(@PathVariable UUID slotId, @Valid @RequestBody SlotRequest body) {
        return admin.reschedule(slotId, body.name(), body.startAt(), body.endAt());
    }

    @Operation(
            summary = "Publish a draft",
            description =
                    "Makes it visible and purchasable. Fails with SLOT_OVERLAP if it overlaps"
                            + " another published slot.")
    @PostMapping("/{slotId}/publish")
    SlotView publish(@PathVariable UUID slotId) {
        return admin.publish(slotId);
    }

    @Operation(
            summary = "Close a slot",
            description = "Stops sales and returns unsold quota to the warehouse.")
    @PostMapping("/{slotId}/close")
    SlotView close(@PathVariable UUID slotId) {
        return admin.close(slotId);
    }

    @Operation(summary = "Get a slot with its items")
    @GetMapping("/{slotId}")
    SlotView get(@PathVariable UUID slotId) {
        return admin.get(slotId);
    }

    @Operation(summary = "All slots (any status) starting on a day, today by default")
    @GetMapping
    List<SlotView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return admin.listByDay(date);
    }

    @Operation(
            summary = "Add a product to a slot",
            description =
                    "Reserves the quota from warehouse stock. Allowed until 10 s before the slot starts.")
    @PostMapping("/{slotId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    SlotView addItem(@PathVariable UUID slotId, @Valid @RequestBody AddItemRequest body) {
        return admin.addItem(slotId, body.productId(), body.salePrice(), body.quota());
    }

    @Operation(
            summary = "Change sale price or quota of an item (until 10 s before the slot starts)")
    @PutMapping("/{slotId}/items/{itemId}")
    SlotView changeItem(
            @PathVariable UUID slotId,
            @PathVariable UUID itemId,
            @Valid @RequestBody ChangeItemRequest body) {
        return admin.changeItem(slotId, itemId, body.salePrice(), body.quota());
    }

    @Operation(
            summary =
                    "Remove an item (until 10 s before the slot starts); its quota goes back to stock")
    @DeleteMapping("/{slotId}/items/{itemId}")
    SlotView removeItem(@PathVariable UUID slotId, @PathVariable UUID itemId) {
        return admin.removeItem(slotId, itemId);
    }

    record SlotRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull Instant startAt,
            @NotNull Instant endAt) {}

    record AddItemRequest(
            @NotNull UUID productId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal salePrice,
            @Min(1) @Max(1_000_000) int quota) {}

    record ChangeItemRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal salePrice,
            @Min(1) @Max(1_000_000) int quota) {}
}
