package com.flashsale.outbox.infrastructure;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the in-process listener retry for transient failures (database blip) but never retries a
 * message a listener deliberately rejected: that one goes to the dead-letter queue immediately.
 */
@Configuration(proxyBeanMethods = false)
class RabbitListenerRetryConfig {

    @Bean
    RabbitListenerRetrySettingsCustomizer skipRetryForRejectedMessages() {
        return settings ->
                settings.setExceptionPredicate(
                        failure -> !causedBy(failure, AmqpRejectAndDontRequeueException.class));
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
