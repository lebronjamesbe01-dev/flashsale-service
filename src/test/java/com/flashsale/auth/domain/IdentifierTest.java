package com.flashsale.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifierTest {

    @Test
    void emailIsLowercasedAndTrimmed() {
        assertThat(Identifier.parse("  An.Nguyen@Example.COM "))
                .contains(new Identifier(IdentifierType.EMAIL, "an.nguyen@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0901234567", "090 123 4567", "+84901234567", "+84 90-123-4567"})
    void vietnamesePhoneFormatsBecomeOneE164Value(String raw) {
        assertThat(Identifier.parse(raw))
                .contains(new Identifier(IdentifierType.PHONE, "+84901234567"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-email@", "abc", "12", "a@b", "0901234567890123", "@x.com"})
    void invalidInputIsRejected(String raw) {
        assertThat(Identifier.parse(raw)).isEmpty();
    }

    @Test
    void maskingHidesMostCharacters() {
        assertThat(Identifier.parse("an@example.com").orElseThrow().masked())
                .isEqualTo("a***@example.com");
        assertThat(Identifier.parse("0901234567").orElseThrow().masked()).isEqualTo("+8490****567");
    }
}
