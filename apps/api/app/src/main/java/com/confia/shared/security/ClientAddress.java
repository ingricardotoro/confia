package com.confia.shared.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The address of a client, and the only door through which a text becomes one (web-edge-foundations
 * design.md, decision 12). {@link #parseLiteral} accepts an IPv4 address written as four decimal
 * parts or an IPv6 literal, and nothing else: a host name is rejected, never resolved, because
 * {@link InetAddress#ofLiteral} does not consult a resolver. Its shorthand forms ({@code 127.1},
 * {@code 1.2.3}, a lone number, a leading zero that some parsers read as octal) and the bracketed
 * IPv6 form are rejected here as well, because a header an attacker writes must have one reading.
 *
 * <p>The address is always held normalized: an IPv6 address that maps an IPv4 one is the IPv4
 * address, and the zone of an IPv6 address is dropped. Equality is therefore the equality of the
 * address every part of the system sees.
 *
 * @param address the normalized address
 */
public record ClientAddress(InetAddress address) {

    private static final Pattern IPV4_FOUR_DECIMAL_PARTS =
            Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");

    public ClientAddress {
        Objects.requireNonNull(address, "address");
        try {
            // getByAddress never resolves a name. For a 16-byte address that maps an IPv4 one it
            // returns the IPv4 address, and it builds an IPv6 address without a zone.
            address = InetAddress.getByAddress(address.getAddress());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("an IP address has four or sixteen bytes");
        }
    }

    /**
     * @throws IllegalArgumentException if {@code literal} is not an IPv4 or IPv6 literal; the
     *     message never repeats the text, which may come from a client
     */
    public static ClientAddress parseLiteral(String literal) {
        Objects.requireNonNull(literal, "literal");
        boolean ipv6 = literal.indexOf(':') >= 0;
        boolean acceptable = ipv6
                ? literal.indexOf('[') < 0 && literal.indexOf(']') < 0
                : IPV4_FOUR_DECIMAL_PARTS.matcher(literal).matches();
        if (!acceptable) {
            throw new IllegalArgumentException("the text is not an IPv4 or IPv6 address literal");
        }
        try {
            return new ClientAddress(InetAddress.ofLiteral(literal));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the text is not an IPv4 or IPv6 address literal");
        }
    }

    /**
     * The key under which this client is counted: an IPv4 address whole, an IPv6 address by its
     * first 64 bits. An IPv6 address that maps an IPv4 one is already the IPv4 address.
     */
    public ClientKey rateLimitKey() {
        byte[] bytes = address.getAddress();
        return new ClientKey(bytes.length == 4 ? bytes : Arrays.copyOf(bytes, 8));
    }

    /** The text written to {@code source_ip}: dotted decimal for IPv4, eight groups for IPv6. */
    public String canonical() {
        return address.getHostAddress();
    }
}
