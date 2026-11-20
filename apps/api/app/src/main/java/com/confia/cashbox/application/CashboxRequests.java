package com.confia.cashbox.application;

import com.confia.cashbox.domain.IdempotencyKeyRequiredException;
import com.confia.cashbox.domain.InvalidCashboxValueException;
import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What every use case of the cash sessions does with the request before the transaction: reads the Idempotency-Key (1 to 128
 * visible characters), makes the identifier of the request for the audit entry and digests what the request asked, including
 * who asked, so that another staff member with the same key and body gets no result.
 */
final class CashboxRequests {

    private static final Pattern KEY = Pattern.compile("[!-~]{1,128}");

    private CashboxRequests() {
    }

    static String key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidCashboxValueException();
        }
        return idempotencyKey;
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
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
