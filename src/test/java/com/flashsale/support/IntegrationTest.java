package com.flashsale.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context against real infrastructure containers, shared by every integration
 * test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        properties = {
            "app.rate-limit.enabled=false",
            // Tests create a slot and read it back at once; caching is covered by a unit test.
            "app.flash-sale.current-cache-ttl=0s",
            "app.auth.jwt-secret=test-only-jwt-secret-0123456789abcdefghijkl",
            "app.auth.otp-hmac-key=test-only-otp-hmac-key-0123456789abcdefghij"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, BlockedShoppersRule.class})
public @interface IntegrationTest {}
