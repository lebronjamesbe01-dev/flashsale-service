package com.flashsale.auth.application;

import com.flashsale.auth.domain.Identifier;
import com.flashsale.auth.domain.NotificationMessageRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo-only: reads the latest mock OTP message so a tester can complete registration from Postman
 * without database access. The bean does not exist outside the {@code demo} profile.
 */
@Service
@Profile("demo")
public class DemoOtpLookup {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final NotificationMessageRepository messages;

    public DemoOtpLookup(NotificationMessageRepository messages) {
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public Optional<SentOtp> latest(Identifier identifier) {
        return messages.findFirstByRecipientOrderByCreatedAtDesc(identifier.value())
                .map(m -> {
                    Matcher matcher = CODE.matcher(m.body());
                    return new SentOtp(
                            identifier.value(),
                            matcher.find() ? matcher.group(1) : null,
                            m.body(),
                            m.createdAt());
                });
    }

    public record SentOtp(String identifier, String otp, String message, Instant sentAt) {}
}
