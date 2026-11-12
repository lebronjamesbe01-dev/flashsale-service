package com.flashsale.auth.application;

import com.flashsale.auth.domain.User;
import com.flashsale.auth.domain.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets other modules ask about accounts without touching the auth module's internals. */
@Service
public class UserDirectory {

    private final UserRepository users;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return users.findById(userId).map(User::isActive).orElse(false);
    }
}
