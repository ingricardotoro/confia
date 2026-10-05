package com.confia.shared.web.request;

import com.confia.shared.security.ClientAddress;
import java.net.Inet4Address;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * An IP address or a CIDR range of the trusted-proxy list (web-edge-foundations design.md,
 * decision 12). A bare address is a block of one. Parsing goes through {@link
 * ClientAddress#parseLiteral}, so a name is never resolved, and an entry that names an IPv6
 * address mapping an IPv4 one is that IPv4 address; with a prefix it is rejected, because the
 * prefix would be ambiguous between the two forms.
 */
final class CidrBlock {

    private static final Pattern PREFIX = Pattern.compile("[0-9]{1,3}");

    private final byte[] network;
    private final int prefix;

    private CidrBlock(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /**
     * @throws IllegalArgumentException if {@code entry} is not an address or an address and a
     *     prefix that fits its family; the message never repeats the entry
     */
    static CidrBlock parse(String entry) {
        int slash = entry.indexOf('/');
        String addressText = slash < 0 ? entry : entry.substring(0, slash);
        ClientAddress address = ClientAddress.parseLiteral(addressText);
        byte[] bytes = address.address().getAddress();
        if (slash < 0) {
            return new CidrBlock(bytes, bytes.length * 8);
        }
        String prefixText = entry.substring(slash + 1);
        if (!PREFIX.matcher(prefixText).matches()) {
            throw new IllegalArgumentException("the prefix is not a number of bits");
        }
        int bits = Integer.parseInt(prefixText);
        if (bits > bytes.length * 8) {
            throw new IllegalArgumentException("the prefix is longer than the address");
        }
        if (addressText.indexOf(':') >= 0 && address.address() instanceof Inet4Address) {
            throw new IllegalArgumentException(
                    "write a range over an IPv4-mapped address in its IPv4 form");
        }
        return new CidrBlock(masked(bytes, bits), bits);
    }

    boolean contains(ClientAddress candidate) {
        byte[] bytes = candidate.address().getAddress();
        if (bytes.length != network.length) {
            return false;
        }
        int wholeBytes = prefix / 8;
        if (!Arrays.equals(bytes, 0, wholeBytes, network, 0, wholeBytes)) {
            return false;
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (bytes[wholeBytes] & mask) == (network[wholeBytes] & 0xFF);
    }

    /** {@code bytes} with every bit after the first {@code bits} cleared. */
    private static byte[] masked(byte[] bytes, int bits) {
        byte[] copy = bytes.clone();
        for (int i = 0; i < copy.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            copy[i] &= (byte) ((0xFF << (8 - keep)) & 0xFF);
        }
        return copy;
    }
}
