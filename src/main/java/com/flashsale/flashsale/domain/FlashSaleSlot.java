package com.flashsale.flashsale.domain;

import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** One time window of the day during which a set of products is sold at flash sale prices. */
@Entity
@Table(name = "flash_sale_slots")
public class FlashSaleSlot {

    static final Duration EDIT_FREEZE_MARGIN = Duration.ofSeconds(10);

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlotStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected FlashSaleSlot() {}

    public static FlashSaleSlot draft(
            String name, Instant startAt, Instant endAt, ZoneId zone, Instant now) {
        validateWindow(startAt, endAt, zone, now);
        FlashSaleSlot slot = new FlashSaleSlot();
        slot.id = UUID.randomUUID();
        slot.name = name;
        slot.startAt = startAt;
        slot.endAt = endAt;
        slot.status = SlotStatus.DRAFT;
        slot.createdAt = now;
        slot.updatedAt = now;
        return slot;
    }

    /**
     * A slot must end after it starts, must not be entirely in the past, and must stay within one
     * business day so that "one flash sale purchase per day" has a single, unambiguous date.
     */
    private static void validateWindow(Instant startAt, Instant endAt, ZoneId zone, Instant now) {
        if (!endAt.isAfter(startAt) || !endAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_SLOT_TIME);
        }
        LocalDate startDay = LocalDate.ofInstant(startAt, zone);
        LocalDate lastDay = LocalDate.ofInstant(endAt.minusNanos(1), zone);
        if (!startDay.equals(lastDay)) {
            throw new BusinessException(ErrorCode.INVALID_SLOT_TIME);
        }
    }

    public void reschedule(String name, Instant startAt, Instant endAt, ZoneId zone, Instant now) {
        validateWindow(startAt, endAt, zone, now);
        this.name = name;
        this.startAt = startAt;
        this.endAt = endAt;
        this.updatedAt = now;
    }

    public void publish(Instant now) {
        this.status = SlotStatus.PUBLISHED;
        this.updatedAt = now;
    }

    public void close(Instant now) {
        this.status = SlotStatus.CLOSED;
        this.updatedAt = now;
    }

    /**
     * Items and quotas may change while the slot is a draft that has not ended, or while it is
     * published but more than {@link #EDIT_FREEZE_MARGIN} away from opening. The margin must exceed
     * the 1 s item cache of the purchase path plus an admin transaction's duration: an edit
     * committed any later could open with the old price still cached.
     */
    public boolean isEditable(Instant now) {
        // An expired draft can never be published, so reserving stock for it would only leak.
        return (status == SlotStatus.DRAFT && endAt.isAfter(now))
                || (status == SlotStatus.PUBLISHED
                        && startAt.minus(EDIT_FREEZE_MARGIN).isAfter(now));
    }

    /** True while shoppers cannot buy yet (draft, or published but not started). */
    public boolean isUpcoming(Instant now) {
        return status == SlotStatus.DRAFT
                || (status == SlotStatus.PUBLISHED && startAt.isAfter(now));
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant startAt() {
        return startAt;
    }

    public Instant endAt() {
        return endAt;
    }

    public SlotStatus status() {
        return status;
    }
}
