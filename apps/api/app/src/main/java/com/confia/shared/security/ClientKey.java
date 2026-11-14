package com.confia.shared.security;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a {@link ClientAddress} under which a client is counted (web-edge-foundations
 * design.md, decision 15): four bytes for an IPv4 address and the first eight for an IPv6 one, so
 * that every address of a /64 shares a key. Two keys are equal exactly when their bytes are, and
 * the bytes never leave the object.
 */
public final class ClientKey {

    private final byte[] bytes;

    ClientKey(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    /**
     * The key of an authenticated account, for the policies that limit per account in addition to per
     * address. It is the identifier of the account behind one marker byte, so it can never equal the key of
     * an address (four or eight bytes) even in a table shared with addresses. It must come from the
     * verified token of the request, never from a header or a body.
     */
    public static ClientKey ofAccount(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put((byte) 0x41).putLong(accountId.getMostSignificantBits())
                .putLong(accountId.getLeastSignificantBits());
        return new ClientKey(buffer.array());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClientKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ClientKey[" + HexFormat.of().formatHex(bytes) + "]";
    }
}
