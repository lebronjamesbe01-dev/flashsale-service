package com.flashsale.auth.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A login identifier: an email address or a phone number, always in canonical form.
 *
 * <p>The single register/login API accepts either; the type is detected from the input. Canonical
 * form makes {@code "An@Mail.com"} and {@code "an@mail.com"}, or {@code "0901 234 567"} and {@code
 * "+84901234567"}, the same account.
 */
public record Identifier(IdentifierType type, String value) {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}$");
    private static final Pattern PHONE_CHARS = Pattern.compile("^\\+?[0-9 ().-]{6,20}$");
    private static final String DEFAULT_REGION = "VN";
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    public static Optional<Identifier> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String input = raw.strip();
        if (input.contains("@")) {
            return EMAIL.matcher(input).matches()
                    ? Optional.of(
                            new Identifier(IdentifierType.EMAIL, input.toLowerCase(Locale.ROOT)))
                    : Optional.empty();
        }
        if (!PHONE_CHARS.matcher(input).matches()) {
            return Optional.empty();
        }
        try {
            PhoneNumber number = PHONES.parse(input, DEFAULT_REGION);
            if (!PHONES.isValidNumber(number)) {
                return Optional.empty();
            }
            String e164 = PHONES.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
            return Optional.of(new Identifier(IdentifierType.PHONE, e164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /** Safe for logs: {@code a***@mail.com}, {@code +8490****567}. */
    public String masked() {
        if (type == IdentifierType.EMAIL) {
            int at = value.indexOf('@');
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.substring(0, Math.min(5, value.length()))
                + "****"
                + value.substring(Math.max(value.length() - 3, 0));
    }
}
