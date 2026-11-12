package com.flashsale.auth.application;

import com.flashsale.auth.domain.User;
import java.util.UUID;

public record UserProfile(UUID id, String identifier, String identifierType, String role) {

    static UserProfile of(User user) {
        return new UserProfile(
                user.id(), user.identifier(), user.identifierType().name(), user.role().name());
    }
}
