package com.flashsale.catalog.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    /** SELECT ... FOR SHARE: a concurrent deactivation waits until the caller commits. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForShareById(@Param("id") UUID id);
}
