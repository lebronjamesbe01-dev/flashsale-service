package com.flashsale.auth.application;

import java.util.UUID;

/**
 * Published inside the activation transaction. Other modules react to it (the wallet module opens a
 * wallet) without the auth module knowing about them.
 */
public record UserActivated(UUID userId) {}
