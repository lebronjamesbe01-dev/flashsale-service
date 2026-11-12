package com.flashsale.auth.domain;

import com.flashsale.shared.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mock delivery channel required by the assignment: instead of calling an email/SMS provider, the
 * message is written to {@code notification_outbox}. A real sender would read, deliver and purge
 * these rows.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationMessage extends AssignedIdEntity {

    @Id private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdentifierType channel;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationMessage() {}

    public static NotificationMessage to(Identifier recipient, String body, Instant now) {
        NotificationMessage message = new NotificationMessage();
        message.id = UUID.randomUUID();
        message.channel = recipient.type();
        message.recipient = recipient.value();
        message.body = body;
        message.createdAt = now;
        return message;
    }

    public String body() {
        return body;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
