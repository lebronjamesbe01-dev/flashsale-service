package com.flashsale.shared.redis;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis is an accelerator here, never the source of truth: every caller has a fallback (rate limit
 * and JWT deny list fail open, the stock gate falls back to database checks). By default Lettuce
 * queues commands while it is reconnecting, so during an outage each call would wait the full
 * command timeout before that fallback kicks in. Rejecting commands while disconnected makes the
 * fallback immediate; reconnection still happens in the background.
 */
@Configuration(proxyBeanMethods = false)
class RedisFailFastConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return options -> options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
    }
}
