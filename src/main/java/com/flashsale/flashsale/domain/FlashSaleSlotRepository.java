package com.flashsale.flashsale.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashSaleSlotRepository extends JpaRepository<FlashSaleSlot, UUID> {

    /** At most one row: live slots cannot overlap (database exclusion constraint). */
    @Query(
            "select s from FlashSaleSlot s where s.status ="
                    + " com.flashsale.flashsale.domain.SlotStatus.PUBLISHED"
                    + " and s.startAt <= :now and s.endAt > :now")
    Optional<FlashSaleSlot> findOpenAt(@Param("now") Instant now);

    /** Slots past their end that were never closed; drafts too, as they hold reserved stock. */
    @Query(
            "select s.id from FlashSaleSlot s where s.status <>"
                    + " com.flashsale.flashsale.domain.SlotStatus.CLOSED and s.endAt <= :now"
                    + " order by s.endAt")
    List<UUID> findEndedNotClosed(@Param("now") Instant now);

    /** Row lock that serialises item edits with closing the slot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FlashSaleSlot s where s.id = :id")
    Optional<FlashSaleSlot> findForUpdateById(@Param("id") UUID id);

    @Query(
            "select s from FlashSaleSlot s where s.status in :statuses"
                    + " and s.startAt >= :from and s.startAt < :to order by s.startAt")
    List<FlashSaleSlot> findStartingBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("statuses") Collection<SlotStatus> statuses);
}
