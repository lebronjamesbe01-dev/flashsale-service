package com.flashsale.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByIdentifier(String identifier);

    /** Returns 0 when the identifier is already taken (including by a concurrent request). */
    @Modifying
    @Query(
            value =
                    "insert into users (id, identifier, identifier_type, password_hash, status,"
                            + " role, created_at, updated_at, version)"
                            + " values (:#{#u.id()}, :#{#u.identifier()},"
                            + " :#{#u.identifierType().name()}, :#{#u.passwordHash()},"
                            + " :#{#u.status().name()}, :#{#u.role().name()}, :#{#u.createdAt()},"
                            + " :#{#u.createdAt()}, 0)"
                            + " on conflict (identifier) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("u") User user);

    @Modifying(flushAutomatically = true)
    @Query(
            "update User u set u.status = com.flashsale.auth.domain.UserStatus.ACTIVE,"
                    + " u.updatedAt = :now, u.version = u.version + 1"
                    + " where u.id = :id and u.status = com.flashsale.auth.domain.UserStatus.PENDING")
    int activateIfPending(@Param("id") UUID id, @Param("now") Instant now);
}
