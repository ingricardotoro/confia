package com.confia.payments.application;

import com.confia.payments.domain.IdempotencyKeyRequiredException;
import com.confia.payments.domain.InvalidPaymentsValueException;
import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What every use case of the payments does with the request before the transaction: reads the Idempotency-Key (1 to 128 visible
 * characters), makes the identifier of the request for the audit entry and digests what the request asked, including who asked,
 * so that another staff member with the same key and body gets no result (INV-24).
 */
final class PaymentRequests {

    /** The time zone of the institution: the day a payment was received is the day in Honduras. */
    static final ZoneId INSTITUTION_ZONE = ZoneId.of("America/Tegucigalpa");

    private static final Pattern KEY = Pattern.compile("[!-~]{1,128}");

    private PaymentRequests() {
    }

    static String key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidPaymentsValueException();
        }
        return idempotencyKey;
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    /** Today in the time zone of the institution. */
    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(INSTITUTION_ZONE));
    }

    /** SHA-256 of the parts, in order and separated, in lowercase hexadecimal. */
    static String hash(String operation, Object... parts) {
        StringBuilder text = new StringBuilder("v1|").append(operation);
        for (Object part : parts) {
            text.append('|').append(part);
        }
        return HexFormat.of().formatHex(Digests.sha256(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
