package com.confia.shared.security;

import java.util.Arrays;
import java.util.HexFormat;

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
