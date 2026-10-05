package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ClientAddress}: the only door through which a text becomes an address, and the key under
 * which the rate limiter will count it (web-edge-foundations design.md, decisions 12 and 15; specs
 * "Las direcciones IPv6 se agrupan por /64"). The key properties compare with a reference that is
 * independent of the code under test: the top 64 bits that the test itself generated, as a {@link BigInteger}.
 */
class ClientAddressPropertiesTest {

    private static final int TRIES = 500;

    @Property(tries = TRIES)
    void twoIpv6AddressesShareAKeyExactlyWhenTheirTop64BitsAreEqual(
            @ForAll("bytes8") byte[] prefix, @ForAll("bytes8") byte[] otherPrefix,
            @ForAll("bytes8") byte[] firstHostPart, @ForAll("bytes8") byte[] secondHostPart,
            @ForAll boolean samePrefix) {
        String first = ipv6(prefix, firstHostPart);
        String second = ipv6(samePrefix ? prefix : otherPrefix, secondHostPart);

        boolean sameKey = ClientAddress.parseLiteral(first).rateLimitKey()
                .equals(ClientAddress.parseLiteral(second).rateLimitKey());

        // The reference reads the bytes this test generated, not what the parser returned.
        BigInteger firstTop = new BigInteger(1, prefix);
        BigInteger secondTop = new BigInteger(1, samePrefix ? prefix : otherPrefix);
        assertThat(sameKey).isEqualTo(firstTop.equals(secondTop));
        if (samePrefix) {
            assertThat(sameKey).isTrue();
        }
    }

    @Property(tries = TRIES)
    void twoIpv4AddressesShareAKeyExactlyWhenTheyAreTheSameAddress(@ForAll("octets") int[] first,
            @ForAll("octets") int[] second, @ForAll boolean lastOctetOnly) {
        if (lastOctetOnly) {
            second = new int[] {first[0], first[1], first[2], second[3]};
        }
        String firstText = dotted(first);
        String secondText = dotted(second);

        boolean sameKey = ClientAddress.parseLiteral(firstText).rateLimitKey()
                .equals(ClientAddress.parseLiteral(secondText).rateLimitKey());

        assertThat(sameKey).isEqualTo(firstText.equals(secondText));
    }

    @Property(tries = TRIES)
    void anIpv4MappedAddressIsTheIpv4ItContainsInEveryRespect(@ForAll("octets") int[] octets) {
        ClientAddress plain = ClientAddress.parseLiteral(dotted(octets));
        ClientAddress mapped = ClientAddress.parseLiteral("::ffff:" + dotted(octets));

        assertThat(mapped).isEqualTo(plain);
        assertThat(mapped.rateLimitKey()).isEqualTo(plain.rateLimitKey());
        assertThat(mapped.canonical()).isEqualTo(dotted(octets));
    }

    @Property(tries = TRIES)
    void aTextWithoutDigitsOrSeparatorsIsAHostNameAndIsNeverAnAddress(
            @ForAll("hostLikeNames") String name) {
        assertThatThrownBy(() -> ClientAddress.parseLiteral(name))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<byte[]> bytes8() {
        return Arbitraries.bytes().array(byte[].class).ofSize(8);
    }

    @Provide
    Arbitrary<int[]> octets() {
        return Arbitraries.integers().between(0, 255).array(int[].class).ofSize(4);
    }

    @Provide
    Arbitrary<String> hostLikeNames() {
        return Arbitraries.strings().withCharRange('g', 'z').ofMinLength(1).ofMaxLength(20);
    }

    @Test
    void theSpecExamplesGroupBySixtyFourBitsAndKeepIpv4Whole() {
        ClientAddress low = ClientAddress.parseLiteral("2001:db8:1:2::1");
        ClientAddress high = ClientAddress.parseLiteral("2001:db8:1:2:ffff:ffff:ffff:ffff");
        ClientAddress nextPrefix = ClientAddress.parseLiteral("2001:db8:1:3::1");

        assertThat(low.rateLimitKey()).isEqualTo(high.rateLimitKey());
        assertThat(low.rateLimitKey()).isNotEqualTo(nextPrefix.rateLimitKey());
        assertThat(ClientAddress.parseLiteral("203.0.113.9").rateLimitKey())
                .isNotEqualTo(ClientAddress.parseLiteral("203.0.113.10").rateLimitKey());
    }

    @Test
    void everyTextualFormOfOneAddressGivesTheSameKeyAndTheSameAddress() {
        ClientKey expectedV6 = ClientAddress.parseLiteral("2001:db8::1").rateLimitKey();
        for (String form : new String[] {"2001:db8::1", "2001:DB8::1", "2001:0db8:0000:0000:0000:0000:0000:0001",
                "2001:db8:0:0:0:0:0:1", "2001:Db8::0001"}) {
            assertThat(ClientAddress.parseLiteral(form).rateLimitKey()).as(form).isEqualTo(expectedV6);
            assertThat(ClientAddress.parseLiteral(form).canonical()).as(form)
                    .isEqualTo("2001:db8:0:0:0:0:0:1");
        }
        ClientAddress plain = ClientAddress.parseLiteral("1.2.3.4");
        for (String form : new String[] {"::ffff:1.2.3.4", "::FFFF:1.2.3.4", "::ffff:0102:0304",
                "0:0:0:0:0:ffff:102:304"}) {
            assertThat(ClientAddress.parseLiteral(form)).as(form).isEqualTo(plain);
            assertThat(ClientAddress.parseLiteral(form).rateLimitKey()).as(form)
                    .isEqualTo(plain.rateLimitKey());
        }
    }

    @Test
    void theCanonicalTextIsWhatTheAuditColumnReceives() {
        assertThat(ClientAddress.parseLiteral("203.0.113.9").canonical()).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.parseLiteral("2001:DB8::1").canonical())
                .isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "ip6-localhost", "example.invalid", "", " ", "1", "127.1",
            "1.2.3", "010.0.0.1", "00.0.0.1", "1.2.3.4.5", "1.2.3.4:80", "[::1]", "[::1]:443",
            " 1.2.3.4", "1.2.3.4 ", "256.1.1.1", "0x7f.0.0.1", "-1.2.3.4", "+1.2.3.4", "::g",
            ":::", "1.2.3.", ".1.2.3", "::ffff:010.0.0.1", "::ffff:1.2.3.04", "::ffff:1.2.3",
            "::1.2.3.04", "::ffff:00.0.0.1", "fe80::1%1", "fe80::1%eth0", "1.2.3.4%1"})
    void aTextThatIsNotAFourPartIpv4OrAnIpv6LiteralIsRejectedAndNeverResolved(String text) {
        // "localhost" resolves on every machine through the hosts file: rejecting it proves the
        // text is never handed to a resolver.
        assertThatThrownBy(() -> ClientAddress.parseLiteral(text))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullAddressIsRejected() {
        assertThatThrownBy(() -> ClientAddress.parseLiteral(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static String ipv6(byte[] prefix, byte[] hostPart) {
        StringBuilder text = new StringBuilder();
        for (int group = 0; group < 8; group++) {
            byte[] source = group < 4 ? prefix : hostPart;
            int offset = (group % 4) * 2;
            text.append(String.format("%02x%02x", source[offset], source[offset + 1]));
            if (group < 7) {
                text.append(':');
            }
        }
        return text.toString();
    }

    private static String dotted(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }
}
