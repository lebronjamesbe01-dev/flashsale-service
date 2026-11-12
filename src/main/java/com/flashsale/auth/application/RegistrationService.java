package com.flashsale.auth.application;

import com.flashsale.auth.domain.Identifier;
import com.flashsale.auth.domain.OtpSender;
import com.flashsale.auth.domain.OtpStore;
import com.flashsale.auth.domain.User;
import com.flashsale.auth.domain.UserRepository;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Register → receive OTP → verify. Every public method answers the same way whether or not the
 * identifier already has an account, so the API cannot be used to discover who is registered.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final OtpStore otpStore;
    private final OtpSender otpSender;
    private final OtpCodec otpCodec;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public RegistrationService(
            UserRepository users,
            OtpStore otpStore,
            OtpSender otpSender,
            OtpCodec otpCodec,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            BusinessClock clock,
            ApplicationEventPublisher events) {
        this.users = users;
        this.otpStore = otpStore;
        this.otpSender = otpSender;
        this.otpCodec = otpCodec;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public void register(Identifier identifier, String rawPassword) {
        // Hash first on every path: skipping it for existing accounts would make them answer
        // faster.
        String passwordHash = passwordEncoder.encode(rawPassword);
        Optional<User> existing = users.findByIdentifier(identifier.value());
        if (existing.isPresent()) {
            User user = existing.get();
            // While a code is live, a second registration must not swap the password: otherwise
            // whoever registers last would own the account once the real owner enters the code.
            if (user.isPending() && !otpStore.exists(identifier)) {
                user.replacePendingPassword(passwordHash, clock.now());
                sendOtpIfCooledDown(identifier);
            } else {
                log.info("Register attempt ignored for {}", identifier.masked());
            }
            return;
        }
        User pending = User.pending(identifier, passwordHash, clock.now());
        // ON CONFLICT DO NOTHING: a concurrent registration of the same identifier simply loses,
        // instead of failing the whole transaction.
        if (users.insertIfAbsent(pending) == 0) {
            log.info("Concurrent registration for {}", identifier.masked());
            return;
        }
        sendOtpIfCooledDown(identifier);
    }

    @Transactional
    public void verifyOtp(Identifier identifier, String code) {
        String expectedHash =
                otpStore.registerAttempt(identifier, properties.otpMaxAttempts())
                        .orElseThrow(() -> new BusinessException(ErrorCode.OTP_INVALID));
        if (!otpCodec.matches(identifier, code, expectedHash)) {
            throw new BusinessException(ErrorCode.OTP_INVALID);
        }
        User user =
                users.findByIdentifier(identifier.value())
                        .orElseThrow(() -> new BusinessException(ErrorCode.OTP_INVALID));
        // Conditional update: two parallel correct verifications activate (and open a wallet) once.
        if (users.activateIfPending(user.id(), clock.now()) == 1) {
            events.publishEvent(new UserActivated(user.id()));
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        otpStore.delete(identifier);
                    }
                });
    }

    @Transactional
    public void resendOtp(Identifier identifier) {
        users.findByIdentifier(identifier.value())
                .filter(User::isPending)
                .ifPresent(user -> sendOtpIfCooledDown(identifier));
    }

    private void sendOtpIfCooledDown(Identifier identifier) {
        if (!otpStore.tryStartResendCooldown(identifier, properties.otpResendCooldown())) {
            return;
        }
        String code = otpCodec.generate();
        otpStore.save(identifier, otpCodec.hash(identifier, code), properties.otpTtl());
        otpSender.send(identifier, code);
    }
}
