package com.flashsale.auth.infrastructure;

import com.flashsale.auth.domain.Identifier;
import com.flashsale.auth.domain.NotificationMessage;
import com.flashsale.auth.domain.NotificationMessageRepository;
import com.flashsale.auth.domain.OtpSender;
import com.flashsale.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock OTP delivery: stores the message in the notification outbox and logs a masked line. */
@Component
class OutboxOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxOtpSender.class);

    private final NotificationMessageRepository messages;
    private final BusinessClock clock;

    OutboxOtpSender(NotificationMessageRepository messages, BusinessClock clock) {
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void send(Identifier recipient, String code) {
        String body = "Your FlashSale verification code is " + code + ". It expires in 5 minutes.";
        messages.save(NotificationMessage.to(recipient, body, clock.now()));
        log.info("OTP queued for {} via {}", recipient.masked(), recipient.type());
    }
}
