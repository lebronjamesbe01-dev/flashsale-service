package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSaleSlotRepository;
import com.flashsale.shared.time.BusinessClock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes slots (published, or drafts nobody published) once their end time has passed, which
 * returns their unsold quota to the warehouse. Safe on every instance at once: the quota freeze
 * only matches rows that still have unsold units, and the slot row lock makes a second closer wait
 * and then see the slot already closed.
 */
@Component
class SlotCloser {

    private static final Logger log = LoggerFactory.getLogger(SlotCloser.class);

    private final FlashSaleSlotRepository slots;
    private final SlotAdminService admin;
    private final BusinessClock clock;

    SlotCloser(FlashSaleSlotRepository slots, SlotAdminService admin, BusinessClock clock) {
        this.slots = slots;
        this.admin = admin;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.flash-sale.slot-close-interval:60s}", initialDelay = 10000)
    void closeEndedSlots() {
        try {
            for (UUID slotId : slots.findEndedNotClosed(clock.now())) {
                try {
                    admin.closeSlot(slotId);
                } catch (ObjectOptimisticLockingFailureException closedByAnotherInstance) {
                    log.debug("Slot {} closed concurrently", slotId);
                } catch (RuntimeException e) {
                    // One bad slot must not starve the others
                    log.error("Closing slot {} failed", slotId, e);
                }
            }
        } catch (DataAccessException e) {
            log.warn("Closing ended slots skipped: {}", e.getMessage());
        }
    }
}
