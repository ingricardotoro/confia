package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.ClientAddress;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link CidrBlock}, the membership test behind the trusted-proxy list (web-edge-foundations
 * design.md, decision 12; specs/web-edge, "La lista de proxies de confianza es una propiedad por
 * entorno"). The properties compare it with a reference that shares no code with it: the network
 * and the candidate as {@link BigInteger}s, shifted right by the number of host bits, are equal
 * exactly when the candidate is inside the block.
 */
class CidrBlockPropertiesTest {

    private static final int TRIES = 500;

    @Property(tries = TRIES)
    void anIpv4BlockAgreesWithTheBigIntegerReference(@ForAll("bytes4") byte[] network,
            @ForAll @IntRange(min = 0, max = 32) int prefix,
            @ForAll("candidatesNear") int flippedBit, @ForAll("bytes4") byte[] unrelated,
            @ForAll boolean nearTheNetwork) throws UnknownHostException {
        byte[] candidate = nearTheNetwork ? flipped(network, flippedBit % 32) : unrelated;

        assertThat(contains(network, prefix, candidate))
                .isEqualTo(referenceContains(network, prefix, candidate));
    }

    @Property(tries = TRIES)
    void anIpv6BlockAgreesWithTheBigIntegerReference(@ForAll("bytes16") byte[] network,
            @ForAll @IntRange(min = 0, max = 128) int prefix,
            @ForAll("candidatesNear") int flippedBit, @ForAll("bytes16") byte[] unrelated,
            @ForAll boolean nearTheNetwork) throws UnknownHostException {
        byte[] candidate = nearTheNetwork ? flipped(network, flippedBit % 128) : unrelated;

        assertThat(contains(network, prefix, candidate))
                .isEqualTo(referenceContains(network, prefix, candidate));
    }

    @Property(tries = TRIES)
    void aBlockNeverContainsAnAddressOfTheOtherFamily(@ForAll("bytes4") byte[] v4,
            @ForAll("bytes16") byte[] v6, @ForAll @IntRange(min = 0, max = 32) int prefix)
            throws UnknownHostException {
        assertThat(contains(v4, prefix, v6)).isFalse();
        assertThat(contains(v6, prefix, v4)).isFalse();
    }

    @Provide
    Arbitrary<byte[]> bytes4() {
        return Arbitraries.bytes().array(byte[].class).ofSize(4);
    }

    @Provide
    Arbitrary<byte[]> bytes16() {
        return Arbitraries.bytes().array(byte[].class).ofSize(16);
    }

    @Provide
    Arbitrary<Integer> candidatesNear() {
        return Arbitraries.integers().between(0, 127);
    }

    @Test
    void aBareAddressIsABlockOfOneAndAPrefixOfZeroCoversItsWholeFamily() {
        CidrBlock host = CidrBlock.parse("10.0.0.7");
        CidrBlock everything = CidrBlock.parse("0.0.0.0/0");

        assertThat(host.contains(ClientAddress.parseLiteral("10.0.0.7"))).isTrue();
        assertThat(host.contains(ClientAddress.parseLiteral("10.0.0.8"))).isFalse();
        assertThat(everything.contains(ClientAddress.parseLiteral("203.0.113.9"))).isTrue();
        assertThat(everything.contains(ClientAddress.parseLiteral("2001:db8::1"))).isFalse();
    }

    @Test
    void theRangeOfTheSpecIncludesItsFirstAndLastAddressAndNothingOutside() {
        CidrBlock block = CidrBlock.parse("10.0.0.0/8");

        assertThat(block.contains(ClientAddress.parseLiteral("10.0.0.0"))).isTrue();
        assertThat(block.contains(ClientAddress.parseLiteral("10.255.255.255"))).isTrue();
        assertThat(block.contains(ClientAddress.parseLiteral("11.0.0.1"))).isFalse();
        assertThat(block.contains(ClientAddress.parseLiteral("9.255.255.255"))).isFalse();
    }

    @Test
    void anIpv4MappedIpv6AddressIsComparedAsTheIpv4ItContains() {
        CidrBlock block = CidrBlock.parse("10.0.0.0/8");

        assertThat(block.contains(ClientAddress.parseLiteral("::ffff:10.1.2.3"))).isTrue();
        assertThat(CidrBlock.parse("::ffff:10.0.0.1")
                .contains(ClientAddress.parseLiteral("10.0.0.1"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "::/129", "10.0.0.0/-1", "10.0.0.0/", "10.0.0.0/+8",
            "10.0.0.0/8/8", "10.0.0.0/ 8", "10.0.0.0/eight", "no-es-una-ip", "no-es-una-ip/8", "",
            "/8", "localhost", "10.0.0.256", "::ffff:10.0.0.0/104"})
    void anInvalidEntryIsRejectedWithoutResolvingAnyName(String entry) {
        assertThatThrownBy(() -> CidrBlock.parse(entry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean contains(byte[] network, int prefix, byte[] candidate)
            throws UnknownHostException {
        String text = InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
        return CidrBlock.parse(text).contains(
                ClientAddress.parseLiteral(InetAddress.getByAddress(candidate).getHostAddress()));
    }

    private static boolean referenceContains(byte[] network, int prefix, byte[] candidate) {
        if (network.length != candidate.length) {
            return false;
        }
        int hostBits = network.length * 8 - prefix;
        return new BigInteger(1, network).shiftRight(hostBits)
                .equals(new BigInteger(1, candidate).shiftRight(hostBits));
    }

    /** {@code bytes} with the bit at {@code position}, counted from the most significant, flipped. */
    private static byte[] flipped(byte[] bytes, int position) {
        byte[] copy = bytes.clone();
        copy[position / 8] ^= (byte) (0x80 >>> (position % 8));
        return copy;
    }
}
