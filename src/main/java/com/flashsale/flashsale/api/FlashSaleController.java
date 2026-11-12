package com.flashsale.flashsale.api;

import com.flashsale.flashsale.application.FlashSaleQueryService;
import com.flashsale.flashsale.application.FlashSaleViews.CurrentSaleView;
import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flash sale")
@RestController
@RequestMapping("/api/v1/flash-sale")
class FlashSaleController {

    private final FlashSaleQueryService queries;

    FlashSaleController(FlashSaleQueryService queries) {
        this.queries = queries;
    }

    @Operation(
            summary = "Products on flash sale right now",
            description =
                    "Returns the slot open at this moment with each item's sale price and remaining"
                            + " quantity. slot is null when no slot is open.")
    @GetMapping("/current")
    CurrentSaleView current() {
        return queries.current();
    }

    @Operation(
            summary = "Flash sale schedule of a day",
            description = "Published slots of the given date (Asia/Ho_Chi_Minh), today by default.")
    @GetMapping("/slots")
    List<SlotView> schedule(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return queries.schedule(date);
    }
}
