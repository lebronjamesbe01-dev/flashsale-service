package com.flashsale.flashsale.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashSaleSlotRepository extends JpaRepository<FlashSaleSlot, UUID> {

    /** At most one row: live slots cannot overlap (database exclusion constraint). */
    @Query(
            "select s from FlashSaleSlot s where s.status ="
                    + " com.flashsale.flashsale.domain.SlotStatus.PUBLISHED"
                    + " and s.startAt <= :now and s.endAt > :now")
    Optional<FlashSaleSlot> findOpenAt(@Param("now") Instant now);

    @Query(
            "select s.id from FlashSaleSlot s where s.status ="
                    + " com.flashsale.flashsale.domain.SlotStatus.PUBLISHED and s.endAt <= :now")
    List<UUID> findEndedButPublished(@Param("now") Instant now);

    @Query(
            "select s from FlashSaleSlot s where s.status in :statuses"
                    + " and s.startAt >= :from and s.startAt < :to order by s.startAt")
    List<FlashSaleSlot> findStartingBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("statuses") Collection<SlotStatus> statuses);
}
