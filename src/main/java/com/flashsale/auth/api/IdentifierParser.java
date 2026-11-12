package com.flashsale.auth.api;

import com.flashsale.auth.domain.Identifier;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;

final class IdentifierParser {

    private IdentifierParser() {}

    static Identifier parse(String raw) {
        return Identifier.parse(raw)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.VALIDATION_FAILED,
                                "identifier must be a valid email address or phone number"));
    }
}
