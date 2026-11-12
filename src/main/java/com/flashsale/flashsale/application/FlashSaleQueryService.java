package com.flashsale.flashsale.application;

import com.flashsale.flashsale.application.FlashSaleViews.CurrentSaleView;
import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import com.flashsale.flashsale.domain.FlashSaleSlotRepository;
import com.flashsale.flashsale.domain.SlotStatus;
import com.flashsale.shared.time.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shopper-facing reads: what is on sale now, and today's schedule. */
@Service
public class FlashSaleQueryService {

    private final FlashSaleSlotRepository slots;
    private final SlotViewAssembler assembler;
    private final BusinessClock clock;

    public FlashSaleQueryService(
            FlashSaleSlotRepository slots, SlotViewAssembler assembler, BusinessClock clock) {
        this.slots = slots;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CurrentSaleView current() {
        Instant now = clock.now();
        SlotView open = slots.findOpenAt(now).map(assembler::one).orElse(null);
        return new CurrentSaleView(now, open);
    }

    /** Published slots of a business day (Asia/Ho_Chi_Minh), in time order. */
    @Transactional(readOnly = true)
    public List<SlotView> schedule(LocalDate day) {
        LocalDate date = day == null ? clock.today() : day;
        Instant from = date.atStartOfDay(clock.zone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(clock.zone()).toInstant();
        return assembler.all(slots.findStartingBetween(from, to, EnumSet.of(SlotStatus.PUBLISHED)));
    }
}
