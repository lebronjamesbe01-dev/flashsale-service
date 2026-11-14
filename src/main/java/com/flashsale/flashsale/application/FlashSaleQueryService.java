package com.flashsale.flashsale.application;

import com.flashsale.flashsale.application.FlashSaleViews.CurrentSaleView;
import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import com.flashsale.flashsale.domain.FlashSaleSlotRepository;
import com.flashsale.flashsale.domain.SlotStatus;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Shopper-facing reads: what is on sale now, and today's schedule. */
@Service
public class FlashSaleQueryService {

    private final FlashSaleSlotRepository slots;
    private final SlotViewAssembler assembler;
    private final BusinessClock clock;
    private final TransactionTemplate readOnlyTx;
    private final OpenSlotCache openSlot;

    public FlashSaleQueryService(
            FlashSaleSlotRepository slots,
            SlotViewAssembler assembler,
            BusinessClock clock,
            PlatformTransactionManager transactions,
            @Value("${app.flash-sale.current-cache-ttl:1s}") Duration currentCacheTtl) {
        this.slots = slots;
        this.assembler = assembler;
        this.clock = clock;
        this.readOnlyTx = new TransactionTemplate(transactions);
        this.readOnlyTx.setReadOnly(true);
        this.openSlot = new OpenSlotCache(currentCacheTtl);
    }

    /**
     * Not transactional on purpose: a cache hit must not borrow a database connection. Only a cache
     * miss opens a read-only transaction.
     */
    public CurrentSaleView current() {
        Instant now = clock.now();
        SlotView open =
                openSlot.get(
                        now,
                        () ->
                                readOnlyTx.execute(tx ->
                                        slots.findOpenAt(now).map(assembler::one).orElse(null)));
        return new CurrentSaleView(now, open);
    }

    /** Published slots of a business day (Asia/Ho_Chi_Minh), in time order. */
    @Transactional(readOnly = true)
    public List<SlotView> schedule(LocalDate day) {
        LocalDate date = day == null ? clock.today() : day;
        BusinessClock.DayRange range = clock.dayRange(date);
        return assembler.all(
                slots.findStartingBetween(
                        range.from(), range.to(), EnumSet.of(SlotStatus.PUBLISHED)));
    }
}
