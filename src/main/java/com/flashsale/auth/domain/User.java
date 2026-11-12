package com.flashsale.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id private UUID id;

    @Column(nullable = false, unique = true)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false)
    private IdentifierType identifierType;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected User() {}

    public static User pending(Identifier identifier, String passwordHash, Instant now) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.identifier = identifier.value();
        user.identifierType = identifier.type();
        user.passwordHash = passwordHash;
        user.status = UserStatus.PENDING;
        user.role = Role.USER;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /** A pending user who registers again replaces the password they could not confirm. */
    public void replacePendingPassword(String newPasswordHash, Instant now) {
        if (status != UserStatus.PENDING) {
            throw new IllegalStateException("Only pending accounts can change password here");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isPending() {
        return status == UserStatus.PENDING;
    }

    public UUID id() {
        return id;
    }

    public String identifier() {
        return identifier;
    }

    public IdentifierType identifierType() {
        return identifierType;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public UserStatus status() {
        return status;
    }

    public Role role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
