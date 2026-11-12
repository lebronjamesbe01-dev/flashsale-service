package com.flashsale.auth.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationMessageRepository extends JpaRepository<NotificationMessage, UUID> {

    Optional<NotificationMessage> findFirstByRecipientOrderByCreatedAtDesc(String recipient);
}
