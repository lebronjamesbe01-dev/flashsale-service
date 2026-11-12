package com.flashsale.auth.api;

import com.flashsale.auth.application.DemoOtpLookup;
import com.flashsale.auth.application.DemoOtpLookup.SentOtp;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Demo", description = "Helpers that exist only in the demo profile")
@RestController
@RequestMapping("/api/v1/demo")
@Profile("demo")
class DemoOtpController {

    private final DemoOtpLookup lookup;

    DemoOtpController(DemoOtpLookup lookup) {
        this.lookup = lookup;
    }

    @Operation(
            summary = "Read the last OTP sent to an identifier (mock inbox)",
            description = "Stands in for opening the email/SMS. Disabled outside the demo profile.")
    @SecurityRequirements
    @GetMapping("/otp")
    SentOtp latestOtp(@RequestParam String identifier) {
        return lookup.latest(IdentifierParser.parse(identifier))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
